package com.paytm.exercise.wallet.domain;

public record Transfer(
        long id,
        String idempotencyKey,
        long fromWalletId,
        long toWalletId,
        long amountPaise,
        TransferStatus status,
        String declineReason
) { }
