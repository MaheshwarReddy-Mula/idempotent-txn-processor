package com.example.txnprocessor.processing;

/**
 * @param maxAttempts   total attempts including the first one (>= 1)
 * @param backoffMillis base delay; doubles on each further attempt (0 disables waiting)
 */
public record RetryPolicy(int maxAttempts, long backoffMillis) {

    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        if (backoffMillis < 0) {
            throw new IllegalArgumentException("backoffMillis must be >= 0");
        }
    }

    public long delayBeforeAttempt(int attempt) {
        if (attempt <= 1 || backoffMillis == 0) {
            return 0;
        }
        return backoffMillis * (1L << Math.min(attempt - 2, 20));
    }
}
