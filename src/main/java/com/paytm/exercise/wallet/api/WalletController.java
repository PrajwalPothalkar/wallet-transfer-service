package com.paytm.exercise.wallet.api;

import com.paytm.exercise.wallet.middleware.DemoAuthentication;
import com.paytm.exercise.wallet.middleware.DemoPrincipal;
import com.paytm.exercise.wallet.observability.DomainEventLogger;
import com.paytm.exercise.wallet.service.WalletService;
import com.paytm.exercise.wallet.shared.Validation;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WalletController {
    private final DemoAuthentication authentication;
    private final WalletService wallets;
    private final DomainEventLogger events;

    public WalletController(DemoAuthentication authentication, WalletService wallets, DomainEventLogger events) {
        this.authentication = authentication;
        this.wallets = wallets;
        this.events = events;
    }

    @PostMapping("/wallets")
    public WalletResponse getOrCreate(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        DemoPrincipal principal = authentication.requirePrincipal(authorization);
        WalletService.GetOrCreateWalletResult result = wallets.getOrCreate(principal.userId());
        events.walletEvent(result.created() ? "wallet_created" : "wallet_get_or_create_replay", result.wallet().id(), principal.userId());
        return result.wallet();
    }

    @GetMapping("/wallets/{id}")
    public WalletResponse get(@PathVariable long id, @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return wallets.balance(Validation.positiveId(id, "wallet"), authentication.requirePrincipal(authorization).userId());
    }
}
