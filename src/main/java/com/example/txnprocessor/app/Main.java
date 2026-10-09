package com.example.txnprocessor.app;

import com.example.txnprocessor.input.CsvTransactionSource;
import com.example.txnprocessor.input.InMemoryTransactionSource;
import com.example.txnprocessor.input.TransactionSource;
import com.example.txnprocessor.processing.ConfigurableFailureInjector;
import com.example.txnprocessor.processing.ProcessingEventLog;
import com.example.txnprocessor.processing.ProcessingReport;
import com.example.txnprocessor.processing.RetryPolicy;
import com.example.txnprocessor.processing.Sleeper;
import com.example.txnprocessor.processing.TransactionProcessor;
import com.example.txnprocessor.store.InMemoryTransactionStore;
import com.example.txnprocessor.validation.TransactionValidator;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;

/** Command-line entry point. Run with --help for options. */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        Path input = null;
        Path reportFile = null;
        int maxAttempts = 3;
        long backoffMillis = 50;
        boolean simulateDefault = true;
        boolean customFailures = false;
        ConfigurableFailureInjector injector = new ConfigurableFailureInjector();

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--input" -> input = Path.of(args[++i]);
                case "--report" -> reportFile = Path.of(args[++i]);
                case "--max-attempts" -> maxAttempts = Integer.parseInt(args[++i]);
                case "--backoff-ms" -> backoffMillis = Long.parseLong(args[++i]);
                case "--fail-before" -> {
                    String[] p = args[++i].split(":");
                    injector.failBeforeCommit(p[0], Integer.parseInt(p[1]));
                    customFailures = true;
                }
                case "--fail-after" -> {
                    String[] p = args[++i].split(":");
                    injector.failAfterCommit(p[0], Integer.parseInt(p[1]));
                    customFailures = true;
                }
                case "--no-simulated-failures" -> simulateDefault = false;
                case "--help" -> {
                    printHelp();
                    return;
                }
                default -> {
                    System.err.println("Unknown option: " + args[i]);
                    printHelp();
                    System.exit(2);
                }
            }
        }

        // Default simulation from the assessment: TXN-2005 fails once, then succeeds on retry.
        if (simulateDefault && !customFailures) {
            injector.failBeforeCommit("TXN-2005", 1);
        }

        TransactionSource source = input == null
                ? new InMemoryTransactionSource(Samples.l2Dataset())
                : new CsvTransactionSource(input);

        ProcessingEventLog log = new ProcessingEventLog(System.out, Clock.systemUTC());
        TransactionProcessor processor = new TransactionProcessor(
                new InMemoryTransactionStore(), new TransactionValidator(), injector,
                new RetryPolicy(maxAttempts, backoffMillis), Sleeper.threadSleep(), log, 1L);

        ProcessingReport report = processor.processAll(source.readAll());

        System.out.println();
        System.out.print(report.toText());

        if (reportFile != null) {
            Path parent = reportFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(reportFile, report.toText(), StandardCharsets.UTF_8);
            System.out.println("Report written to " + reportFile);
        }
    }

    private static void printHelp() {
        System.out.println("""
                Usage: Main [options]
                  --input <file.csv>        CSV input (default: built-in L2 sample data)
                  --report <file>           also write the processing report to a file
                  --max-attempts <n>        total attempts per transaction (default 3)
                  --backoff-ms <n>          base retry backoff, doubles each retry (default 50)
                  --fail-before <txn:n>     simulate n transient failures BEFORE commit for a transaction
                  --fail-after <txn:n>      simulate n lost acknowledgements AFTER commit for a transaction
                  --no-simulated-failures   disable the default simulation (TXN-2005 fails once)
                """);
    }
}
