package com.example.txnprocessor.model;

/** Persistent state of one unique business transaction (keyed by transactionId). */
public class TransactionRecord {
    private final TransactionRequest request;
    private final InputResult result;
    private int duplicateCount;

    public TransactionRecord(TransactionRequest request, InputResult result) {
        this.request = request;
        this.result = result;
    }

    public TransactionRequest request() { return request; }
    public InputResult result() { return result; }
    public TransactionStatus status() { return result.status(); }
    public int duplicateCount() { return duplicateCount; }
    public void incrementDuplicates() { duplicateCount++; }

    public void transition(TransactionStatus status, String reason) {
        result.transition(status, reason);
    }
}
