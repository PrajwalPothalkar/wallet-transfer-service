package com.paytm.exercise.wallet.domain;

import com.fasterxml.jackson.annotation.JsonValue;

public enum TransferStatus {
    COMPLETED("completed"),
    DECLINED("declined");

    private final String value;

    TransferStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }
}
