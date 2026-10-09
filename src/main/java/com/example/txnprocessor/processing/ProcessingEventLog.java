package com.example.txnprocessor.processing;

import java.io.PrintStream;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Structured key=value event log (easy to ship to ELK/Splunk).
 * Sensitive data rules: account ids are masked, amounts are never logged, raw input values are sanitised.
 */
public class ProcessingEventLog {

    private final PrintStream out;
    private final Clock clock;
    private final List<String> lines = new ArrayList<>();
    private final Map<String, Integer> counts = new HashMap<>();

    /** @param out where to print lines; null keeps events in memory only (used by tests) */
    public ProcessingEventLog(PrintStream out, Clock clock) {
        this.out = out;
        this.clock = clock;
    }

    public synchronized void event(String name, Object... keyValues) {
        StringBuilder sb = new StringBuilder("ts=").append(clock.instant()).append(" event=").append(name);
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            sb.append(' ').append(keyValues[i]).append('=').append(clean(keyValues[i + 1]));
        }
        String line = sb.toString();
        lines.add(line);
        counts.merge(name, 1, Integer::sum);
        if (out != null) {
            out.println(line);
        }
    }

    public synchronized List<String> lines() {
        return Collections.unmodifiableList(new ArrayList<>(lines));
    }

    public synchronized int countOf(String eventName) {
        return counts.getOrDefault(eventName, 0);
    }

    public static String maskAccount(String accountId) {
        if (accountId == null || accountId.length() <= 3) {
            return "***";
        }
        return "***" + accountId.substring(accountId.length() - 3);
    }

    /** Makes an untrusted raw value safe for logs and reports (no control chars, bounded length). */
    public static String safe(String raw) {
        if (raw == null || raw.isBlank()) {
            return "-";
        }
        String s = raw.trim();
        if (s.length() > 40) {
            s = s.substring(0, 40);
        }
        return s.replaceAll("[^A-Za-z0-9._-]", "?");
    }

    private static String clean(Object value) {
        String s = String.valueOf(value).replaceAll("[\\r\\n\\t]+", " ");
        if (s.length() > 120) {
            s = s.substring(0, 120);
        }
        return s.indexOf(' ') >= 0 ? "\"" + s.replace("\"", "'") + "\"" : s;
    }
}
