package com.example.txnprocessor.store;

/** A non-retryable business failure (e.g. insufficient funds). Retrying cannot help. */
public class BusinessRuleException extends RuntimeException {
    private final String code;

    public BusinessRuleException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
