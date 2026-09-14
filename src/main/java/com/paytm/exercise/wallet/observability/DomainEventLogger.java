package com.paytm.exercise.wallet.observability;

import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class DomainEventLogger {
    private static final Logger log = LoggerFactory.getLogger(DomainEventLogger.class);

    public void walletEvent(String event, long walletId, String userId) {
        log.info("wallet domain event {}, {}, {}", StructuredArguments.kv("event", event),
                StructuredArguments.kv("wallet_id", walletId), StructuredArguments.kv("user_id", userId));
    }

    public void transferEvent(String event, long transferId, long from, long to, long amountPaise, String status) {
        log.info("transfer domain event {}, {}, {}, {}, {}, {}", StructuredArguments.kv("event", event),
                StructuredArguments.kv("transfer_id", transferId), StructuredArguments.kv("from_wallet_id", from),
                StructuredArguments.kv("to_wallet_id", to), StructuredArguments.kv("amount_paise", amountPaise),
                StructuredArguments.kv("status", status));
    }
}
