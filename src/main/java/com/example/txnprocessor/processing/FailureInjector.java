package com.example.txnprocessor.processing;

import com.example.txnprocessor.model.TransactionRequest;

/**
 * Test/simulation seam for transient failures.
 * beforeCommit = failure before the business effect was applied (e.g. DB timeout).
 * afterCommit  = effect WAS applied but the acknowledgement was lost (the classic duplicate-risk case).
 */
public interface FailureInjector {

    default void beforeCommit(TransactionRequest request, int attempt) {
    }

    default void afterCommit(TransactionRequest request, int attempt) {
    }

    static FailureInjector none() {
        return new FailureInjector() { };
    }
}
