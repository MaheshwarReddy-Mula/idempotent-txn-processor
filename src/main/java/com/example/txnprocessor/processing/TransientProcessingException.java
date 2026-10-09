package com.example.txnprocessor.processing;

/** A failure that may succeed on retry (timeout, downstream unavailable, ...). */
public class TransientProcessingException extends RuntimeException {
    public TransientProcessingException(String message) {
        super(message);
    }
}
