package com.example.txnprocessor.store;

public enum CommitResult {
    /** The ledger/balance was updated by this call. */
    APPLIED,
    /** The transaction had already been applied earlier; nothing changed (idempotent no-op). */
    ALREADY_APPLIED
}
