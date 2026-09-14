package com.paytm.exercise.wallet.api;

import com.paytm.exercise.wallet.domain.TransferStatus;
import com.paytm.exercise.wallet.middleware.DemoAuthentication;
import com.paytm.exercise.wallet.observability.DomainEventLogger;
import com.paytm.exercise.wallet.observability.WalletMetrics;
import com.paytm.exercise.wallet.service.TransferService;
import com.paytm.exercise.wallet.shared.Validation;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TransferController {
    private final DemoAuthentication authentication;
    private final TransferService transfers;
    private final WalletMetrics metrics;
    private final DomainEventLogger events;

    public TransferController(DemoAuthentication authentication, TransferService transfers, WalletMetrics metrics, DomainEventLogger events) {
        this.authentication = authentication;
        this.transfers = transfers;
        this.metrics = metrics;
        this.events = events;
    }

    @PostMapping("/transfers")
    public TransferResponse create(@RequestBody CreateTransferRequest request,
                                   @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        Validation.validTransfer(request);
        return record(transfers.create(request, authentication.requirePrincipal(authorization).userId()));
    }

    @GetMapping("/transfers/{id}")
    public TransferResponse get(@PathVariable long id,
                                @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return transfers.get(Validation.positiveId(id, "transfer"), authentication.requirePrincipal(authorization).userId());
    }

    private TransferResponse record(TransferService.TransferExecution result) {
        TransferResponse transfer = result.transfer();
        if (result.replayed()) metrics.idempotentReplay();
        else if (transfer.status() == TransferStatus.DECLINED) metrics.transferDeclined();
        else metrics.transferCompleted();
        for (String event : result.events()) {
            events.transferEvent(event, transfer.id(), transfer.from(), transfer.to(), transfer.amountPaise(),
                    transfer.status().value());
        }
        return transfer;
    }
}
