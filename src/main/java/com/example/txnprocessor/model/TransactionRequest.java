package com.example.txnprocessor.model;

import java.math.BigDecimal;

/** A validated transaction request. */
public record TransactionRequest(
        String transactionId,
        String requestId,
        long sequenceNumber,
        String accountId,
        TransactionType type,
        BigDecimal amount,
        String currency,
        String referenceTransactionId) {

    /**
     * Business fingerprint used to tell a true replay (same payload) from a conflicting request
     * that re-uses an id. The requestId is deliberately excluded: retries/replays may carry a new one.
     */
    public String fingerprint() {
        return String.join("|",
                accountId,
                type.name(),
                amount.stripTrailingZeros().toPlainString(),
                currency,
                Long.toString(sequenceNumber),
                referenceTransactionId == null ? "" : referenceTransactionId);
    }
}
