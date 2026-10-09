package com.example.txnprocessor.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An un-validated record exactly as it came from the input source.
 * Keeping the raw form lets malformed records be isolated instead of crashing the batch.
 *
 * @param inputOrder  1-based position of the record in the input
 * @param fields      field name -> raw string value (may contain nulls / junk)
 * @param sourceError set when the source itself could not parse the record (e.g. wrong column count)
 */
public record RawRecord(int inputOrder, Map<String, String> fields, String sourceError) {

    public static final String TRANSACTION_ID = "transactionId";
    public static final String REQUEST_ID = "requestId";
    public static final String SEQUENCE_NUMBER = "sequenceNumber";
    public static final String ACCOUNT_ID = "accountId";
    public static final String TRANSACTION_TYPE = "transactionType";
    public static final String AMOUNT = "amount";
    public static final String CURRENCY = "currency";
    public static final String REFERENCE_TRANSACTION_ID = "referenceTransactionId";

    public RawRecord {
        fields = fields == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    public String get(String name) {
        return fields.get(name);
    }

    public static RawRecord of(int order, String transactionId, String requestId, String sequence,
                               String accountId, String type, String amount, String currency) {
        return of(order, transactionId, requestId, sequence, accountId, type, amount, currency, null);
    }

    public static RawRecord of(int order, String transactionId, String requestId, String sequence,
                               String accountId, String type, String amount, String currency,
                               String referenceTransactionId) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(TRANSACTION_ID, transactionId);
        m.put(REQUEST_ID, requestId);
        m.put(SEQUENCE_NUMBER, sequence);
        m.put(ACCOUNT_ID, accountId);
        m.put(TRANSACTION_TYPE, type);
        m.put(AMOUNT, amount);
        m.put(CURRENCY, currency);
        m.put(REFERENCE_TRANSACTION_ID, referenceTransactionId);
        return new RawRecord(order, m, null);
    }

    public static RawRecord malformed(int order, String error) {
        return new RawRecord(order, Map.of(), error);
    }
}
