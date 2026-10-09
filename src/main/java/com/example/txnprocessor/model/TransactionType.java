package com.example.txnprocessor.model;

/** Supported business transaction types. */
public enum TransactionType {
    CREDIT,
    DEBIT,
    /** Reverses a previously PROCESSED transaction (referenced by referenceTransactionId). */
    REVERSAL
}
