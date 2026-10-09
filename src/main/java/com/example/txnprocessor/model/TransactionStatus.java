package com.example.txnprocessor.model;

/**
 * Lifecycle of a transaction / input record.
 *
 * <pre>
 * RECEIVED -> PENDING_SEQUENCE -> PROCESSING -> PROCESSED
 *                                     |  ^
 *                                     v  |
 *                                RETRY_PENDING -> (retries exhausted) FAILED
 * PROCESSING -> FAILED            (non-retryable business rule failure)
 * RECEIVED   -> REJECTED          (validation / conflicting request, never processed)
 * RECEIVED   -> DUPLICATE         (same transaction seen again, never re-applied)
 * </pre>
 */
public enum TransactionStatus {
    RECEIVED,
    /** Arrived ahead of its turn; buffered until all earlier sequence numbers are resolved. */
    PENDING_SEQUENCE,
    PROCESSING,
    PROCESSED,
    /** A transient failure happened; another attempt is scheduled. */
    RETRY_PENDING,
    /** Terminal: retries exhausted or non-retryable business failure. */
    FAILED,
    /** Terminal: a repeat of an already known transaction / request. No side effects. */
    DUPLICATE,
    /** Terminal: invalid input or conflicting request. Isolated, never processed. */
    REJECTED
}
