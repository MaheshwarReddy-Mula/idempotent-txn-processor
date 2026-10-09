package com.example.txnprocessor.processing;

@FunctionalInterface
public interface Sleeper {
    void sleep(long millis);

    static Sleeper threadSleep() {
        return millis -> {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
    }
}
