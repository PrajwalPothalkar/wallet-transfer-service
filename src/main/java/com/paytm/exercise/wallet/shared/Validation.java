package com.paytm.exercise.wallet.shared;

import com.paytm.exercise.wallet.api.CreateTransferRequest;
import org.springframework.http.HttpStatus;

public final class Validation {
    private Validation() { }

    public static void validTransfer(CreateTransferRequest request) {
        if (request.from() <= 0 || request.to() <= 0 || request.amountPaise() <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_request", "from, to, and amount_paise must be positive integers");
        }
        if (request.from() == request.to()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_request", "from and to must differ");
        }
        idempotencyKey(request.idempotencyKey());
    }

    public static String idempotencyKey(String key) {
        if (key == null || key.isBlank() || key.length() > 255) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_request",
                    "idempotency_key must be a non-empty string up to 255 characters");
        }
        return key;
    }

    public static long positiveId(long id, String resource) {
        if (id <= 0) throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_request", resource + " id must be positive");
        return id;
    }
}
