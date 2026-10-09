package com.example.txnprocessor.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Outcome of one input record, including its full status history.
 * Exactly one InputResult exists per input record, so summary counts always add up to the input size.
 */
public class InputResult {
    private final int inputOrder;
    private final String transactionId;
    private final String requestId;
    private TransactionStatus status;
    private String reason;
    private int attempts;
    private final List<TransactionStatus> history = new ArrayList<>();

    public InputResult(int inputOrder, String transactionId, String requestId) {
        this.inputOrder = inputOrder;
        this.transactionId = transactionId;
        this.requestId = requestId;
    }

    public void transition(TransactionStatus newStatus, String newReason) {
        this.status = newStatus;
        this.reason = newReason;
        this.history.add(newStatus);
    }

    public void setAttempts(int attempts) { this.attempts = attempts; }

    public int inputOrder() { return inputOrder; }
    public String transactionId() { return transactionId; }
    public String requestId() { return requestId; }
    public TransactionStatus status() { return status; }
    public String reason() { return reason; }
    public int attempts() { return attempts; }
    public List<TransactionStatus> history() { return Collections.unmodifiableList(history); }
}
