package com.example.txnprocessor.input;

import com.example.txnprocessor.model.RawRecord;

import java.util.List;

/**
 * Replaceable input boundary. A Kafka/SQS/DB-polling adapter would implement the same contract;
 * the processing core never knows where records came from.
 */
public interface TransactionSource {
    List<RawRecord> readAll();
}
