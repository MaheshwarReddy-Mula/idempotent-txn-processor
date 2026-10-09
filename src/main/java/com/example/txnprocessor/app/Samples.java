package com.example.txnprocessor.app;

import com.example.txnprocessor.model.RawRecord;

import java.util.List;

/** Built-in copy of the L2 suggested test data from the assessment document (section 9.8). */
public final class Samples {

    private Samples() {
    }

    public static List<RawRecord> l2Dataset() {
        return List.of(
                RawRecord.of(1, "TXN-2002", "REQ-2",     "2", "ACC-201", "DEBIT",  "40",  "INR"),
                RawRecord.of(2, "TXN-2001", "REQ-1",     "1", "ACC-201", "CREDIT", "100", "INR"),
                RawRecord.of(3, "TXN-2002", "REQ-2-DUP", "2", "ACC-201", "DEBIT",  "40",  "INR"),
                RawRecord.of(4, "TXN-2003", "REQ-3",     "3", "ACC-201", "CREDIT", "25",  "INR"),
                RawRecord.of(5, "TXN-2004", "REQ-4",     "4", "ACC-202", "DEBIT",  "-10", "INR"),
                RawRecord.of(6, "TXN-2005", "REQ-5",     "5", "ACC-203", "CREDIT", "500", "INR"));
    }
}
