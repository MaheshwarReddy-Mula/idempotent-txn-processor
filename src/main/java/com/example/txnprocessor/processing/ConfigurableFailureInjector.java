package com.example.txnprocessor.processing;

import com.example.txnprocessor.model.TransactionRequest;

import java.util.HashMap;
import java.util.Map;

/** Fails the first N attempts of chosen transactions. Deterministic: depends only on the attempt number. */
public class ConfigurableFailureInjector implements FailureInjector {

    private final Map<String, Integer> failBefore = new HashMap<>();
    private final Map<String, Integer> failAfter = new HashMap<>();

    public ConfigurableFailureInjector failBeforeCommit(String transactionId, int times) {
        failBefore.put(transactionId, times);
        return this;
    }

    public ConfigurableFailureInjector failAfterCommit(String transactionId, int times) {
        failAfter.put(transactionId, times);
        return this;
    }

    @Override
    public void beforeCommit(TransactionRequest request, int attempt) {
        if (attempt <= failBefore.getOrDefault(request.transactionId(), 0)) {
            throw new TransientProcessingException("simulated transient failure before commit");
        }
    }

    @Override
    public void afterCommit(TransactionRequest request, int attempt) {
        if (attempt <= failAfter.getOrDefault(request.transactionId(), 0)) {
            throw new TransientProcessingException("simulated lost acknowledgement after commit");
        }
    }
}
