package com.example.txnprocessor.input;

import com.example.txnprocessor.model.RawRecord;

import java.util.List;

public class InMemoryTransactionSource implements TransactionSource {
    private final List<RawRecord> records;

    public InMemoryTransactionSource(List<RawRecord> records) {
        this.records = List.copyOf(records);
    }

    @Override
    public List<RawRecord> readAll() {
        return records;
    }
}
