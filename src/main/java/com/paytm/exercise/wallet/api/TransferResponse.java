package com.paytm.exercise.wallet.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.paytm.exercise.wallet.domain.TransferStatus;

public record TransferResponse(
        long id,
        long from,
        long to,
        @JsonProperty("amount_paise") long amountPaise,
        TransferStatus status,
        @JsonProperty("decline_reason") String declineReason
) { }
