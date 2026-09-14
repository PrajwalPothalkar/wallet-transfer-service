package com.paytm.exercise.wallet.service;

import com.paytm.exercise.wallet.api.WalletResponse;
import com.paytm.exercise.wallet.config.WalletProperties;
import com.paytm.exercise.wallet.domain.Wallet;
import com.paytm.exercise.wallet.repository.WalletRepository;
import com.paytm.exercise.wallet.shared.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class WalletService {
    private final WalletRepository wallets;
    private final WalletProperties properties;

    public WalletService(WalletRepository wallets, WalletProperties properties) {
        this.wallets = wallets;
        this.properties = properties;
    }

    public GetOrCreateWalletResult getOrCreate(String userId) {
        WalletRepository.GetOrCreateResult result = wallets.getOrCreate(userId, properties.initialBalancePaise());
        return new GetOrCreateWalletResult(response(result.wallet()), result.created());
    }

    public WalletResponse balance(long walletId, String userId) {
        Wallet wallet = wallets.findOwned(walletId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "wallet_not_found", "wallet not found"));
        return response(wallet);
    }

    private WalletResponse response(Wallet wallet) {
        return new WalletResponse(wallet.id(), wallet.balancePaise());
    }

    public record GetOrCreateWalletResult(WalletResponse wallet, boolean created) { }
}
