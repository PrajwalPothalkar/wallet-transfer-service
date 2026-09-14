package com.paytm.exercise.wallet.api;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CreateTransferRequest(
        long from,
        long to,
        @JsonProperty("amount_paise") long amountPaise,
        @JsonProperty("idempotency_key") String idempotencyKey
) { }
