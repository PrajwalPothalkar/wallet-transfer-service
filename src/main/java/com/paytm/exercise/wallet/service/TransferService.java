package com.paytm.exercise.wallet.service;

import com.paytm.exercise.wallet.api.CreateTransferRequest;
import com.paytm.exercise.wallet.api.TransferResponse;
import com.paytm.exercise.wallet.domain.Transfer;
import com.paytm.exercise.wallet.domain.Wallet;
import com.paytm.exercise.wallet.repository.TransferRepository;
import com.paytm.exercise.wallet.repository.WalletRepository;
import com.paytm.exercise.wallet.shared.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class TransferService {
    private static final String INSUFFICIENT_FUNDS = "insufficient_funds";

    private final WalletRepository wallets;
    private final TransferRepository transfers;

    public TransferService(WalletRepository wallets, TransferRepository transfers) {
        this.wallets = wallets;
        this.transfers = transfers;
    }

    /**
     * One transaction owns the complete money operation. Do not split this into separately
     * transactional debit/credit/idempotency methods: they must commit or roll back together.
     */
    @Transactional
    public TransferExecution create(CreateTransferRequest request, String callerUserId) {
        Transfer alreadyCommitted = transfers.findByIdempotencyKey(request.idempotencyKey()).orElse(null);
        if (alreadyCommitted != null) {
            requireSameMovement(alreadyCommitted, request.from(), request.to(), request.amountPaise());
            requireCallerOwns(request.from(), callerUserId);
            return TransferExecution.replay(response(alreadyCommitted));
        }

        Map<Long, Wallet> locked = lockPair(request.from(), request.to());
        Wallet source = locked.get(request.from());
        if (!source.userId().equals(callerUserId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "caller does not own the source wallet");
        }

        Transfer claimed = transfers.claim(request.idempotencyKey(), request.from(), request.to(), request.amountPaise())
                .orElse(null);
        if (claimed == null) {
            return TransferExecution.replay(response(
                    replayLoser(request.idempotencyKey(), request.from(), request.to(), request.amountPaise())));
        }
        return applyMovement(claimed, TransferExecution::completed);
    }

    /**
     * The single decision point for every money movement in the system. Rows-affected on the
     * conditional debit is the authoritative answer to "can this wallet afford it?", so an
     * overdraft is unrepresentable and a decline can never partially apply.
     */
    private TransferExecution applyMovement(Transfer claimed, Function<TransferResponse, TransferExecution> onSuccess) {
        if (!wallets.conditionalDebit(claimed.fromWalletId(), claimed.amountPaise())) {
            return TransferExecution.declined(response(transfers.markDeclined(claimed.id(), INSUFFICIENT_FUNDS)));
        }
        wallets.credit(claimed.toWalletId(), claimed.amountPaise());
        return onSuccess.apply(response(transfers.markCompleted(claimed.id())));
    }

    /** Lost the unique-index race; PostgreSQL made us wait for the winner, so read its result. */
    private Transfer replayLoser(String idempotencyKey, long from, long to, long amountPaise) {
        Transfer existing = transfers.findByIdempotencyKey(idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("idempotency row disappeared after conflict"));
        requireSameMovement(existing, from, to, amountPaise);
        return existing;
    }

    private Map<Long, Wallet> lockPair(long from, long to) {
        List<Wallet> locked = wallets.lockPair(from, to);
        if (locked.size() != 2) {
            throw new ApiException(HttpStatus.NOT_FOUND, "wallet_not_found",
                    "source or destination wallet does not exist");
        }
        return locked.stream().collect(Collectors.toMap(Wallet::id, Function.identity()));
    }

    public TransferResponse get(long transferId, String callerUserId) {
        return response(transfers.findVisible(transferId, callerUserId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "transfer_not_found", "transfer not found")));
    }

    private void requireCallerOwns(long walletId, String callerUserId) {
        if (wallets.findOwned(walletId, callerUserId).isEmpty()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "caller does not own the source wallet");
        }
    }

    private void requireSameMovement(Transfer transfer, long from, long to, long amountPaise) {
        if (transfer.fromWalletId() != from || transfer.toWalletId() != to || transfer.amountPaise() != amountPaise) {
            throw conflictingKey();
        }
    }

    private ApiException conflictingKey() {
        return new ApiException(HttpStatus.CONFLICT, "idempotency_key_reused",
                "idempotency_key was already used with a different transfer body");
    }

    private TransferResponse response(Transfer transfer) {
        return new TransferResponse(transfer.id(), transfer.fromWalletId(), transfer.toWalletId(),
                transfer.amountPaise(), transfer.status(), transfer.declineReason());
    }

    public record TransferExecution(TransferResponse transfer, boolean replayed, List<String> events) {
        static TransferExecution replay(TransferResponse transfer) {
            return new TransferExecution(transfer, true, List.of("idempotent_replay_hit"));
        }
        static TransferExecution declined(TransferResponse transfer) {
            return new TransferExecution(transfer, false, List.of(
                    "transfer_created", "transfer_declined_insufficient_funds"));
        }
        static TransferExecution completed(TransferResponse transfer) {
            return new TransferExecution(transfer, false,
                    List.of("transfer_created", "wallet_debited", "wallet_credited", "transfer_completed"));
        }
    }
}
