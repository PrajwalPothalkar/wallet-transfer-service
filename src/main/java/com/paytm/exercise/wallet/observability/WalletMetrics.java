package com.paytm.exercise.wallet.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class WalletMetrics {
    // Meter names deliberately avoid a trailing ".created": Micrometer's Prometheus naming
    // convention strips reserved suffixes, which silently renamed this counter in an earlier
    // revision and made the documented metric name wrong.
    private final Counter transfersCompleted;
    private final Counter transfersDeclined;
    private final Counter idempotentReplays;

    public WalletMetrics(MeterRegistry registry) {
        transfersCompleted = Counter.builder("wallet.transfers.completed")
                .description("Transfers applied to the ledger").register(registry);
        transfersDeclined = Counter.builder("wallet.transfers.declined.insufficient.funds")
                .description("Movements declined for insufficient funds").register(registry);
        idempotentReplays = Counter.builder("wallet.idempotent.replays")
                .description("Requests answered from a committed idempotency key").register(registry);
    }

    public void transferCompleted() { transfersCompleted.increment(); }
    public void transferDeclined() { transfersDeclined.increment(); }
    public void idempotentReplay() { idempotentReplays.increment(); }
}
