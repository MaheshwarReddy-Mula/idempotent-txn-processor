# Idempotent Background Transaction Processing Service

A lightweight Java 17 service that processes transaction requests (CREDIT / DEBIT / REVERSAL) that can
arrive **duplicated, out of order, malformed, or fail transiently**, and still ends in a correct, explainable
state. Built for the FDE L2 practical assessment.

* **Idempotent**: each business transaction changes balance/ledger at most once.
* **Order tolerant**: sequence-based buffering (Option A) with missing-sequence reporting.
* **Retry safe**: bounded retries, exponential backoff, failure after retries exhausted, no double effects.
* **Observable**: structured `key=value` logs (masked accounts, no amounts) and a processing report.
* **Dependency-free**: pure JDK at runtime; JUnit 5 for tests. In-memory store behind an interface.

Full design explanation: [DESIGN.md](DESIGN.md).

## Requirements

* JDK 17 or newer
* Maven 3.8+ (only needed to build/test; the code itself has no runtime dependencies)

## Build and test

```bash
mvn clean test
```

## Run

```bash
mvn -q compile exec:java                      # built-in L2 sample data (section 9.8 of the assessment)

# or build once and run the jar classes directly
mvn -q compile
java -cp target/classes com.example.txnprocessor.app.Main --input data/sample-input.csv
java -cp target/classes com.example.txnprocessor.app.Main --input data/edge-cases-input.csv --fail-after TXN-3006:1
```

Options:

| Option | Meaning |
|---|---|
| `--input <file.csv>` | CSV input. Default: built-in sample data |
| `--report <file>` | Also write the report to a file |
| `--max-attempts <n>` | Total attempts per transaction (default 3) |
| `--backoff-ms <n>` | Base retry backoff, doubles per retry (default 50) |
| `--fail-before <txn:n>` | Simulate *n* transient failures **before** the commit |
| `--fail-after <txn:n>` | Simulate *n* lost acknowledgements **after** the commit (the dangerous duplicate case) |
| `--no-simulated-failures` | Disable the default simulation (`TXN-2005` fails once, then succeeds on retry) |

Input CSV columns: `transactionId, requestId, sequenceNumber, accountId, transactionType, amount, currency, referenceTransactionId`
(the last one is only needed for `REVERSAL`). Lines starting with `#` are comments.

## Sample input and output

Input ([data/sample-input.csv](data/sample-input.csv)) - deliberately out of order:

```
TXN-2002,REQ-2,2,ACC-201,DEBIT,40,INR,
TXN-2001,REQ-1,1,ACC-201,CREDIT,100,INR,
TXN-2002,REQ-2-DUP,2,ACC-201,DEBIT,40,INR,
TXN-2003,REQ-3,3,ACC-201,CREDIT,25,INR,
TXN-2004,REQ-4,4,ACC-202,DEBIT,-10,INR,
TXN-2005,REQ-5,5,ACC-203,CREDIT,500,INR,
```

Result (full logs + report in [data/sample-output.txt](data/sample-output.txt)):

```
Summary by status
  PROCESSED         : 4
  DUPLICATE         : 1
  REJECTED          : 1

  #   transactionId requestId      status            attempts detail
  1   TXN-2002      REQ-2          PROCESSED         1
  2   TXN-2001      REQ-1          PROCESSED         1
  3   TXN-2002      REQ-2-DUP      DUPLICATE         0        duplicate of request REQ-2 ...
  4   TXN-2003      REQ-3          PROCESSED         1
  5   TXN-2004      REQ-4          REJECTED          0        amount must be greater than zero
  6   TXN-2005      REQ-5          PROCESSED         2

Lifecycle: TXN-2005: RECEIVED -> PROCESSING -> RETRY_PENDING -> PROCESSING -> PROCESSED
Balances: ***201 INR = 85.00, ***203 INR = 500.00
```

`TXN-2002` arrived before `TXN-2001`, so it was buffered (`PENDING_SEQUENCE`) and applied after it. Applying it
immediately would have overdrawn the account. [data/edge-cases-output.txt](data/edge-cases-output.txt) shows a
reversal, insufficient funds, a malformed line, an invalid currency, a conflicting duplicate, a lost-ack retry and a
sequence gap.

## Tests

`src/test/java` covers duplicates (replay, new requestId, duplicate while pending, conflicting payload, key reuse),
out-of-order (buffering, reversed order, gaps, stale slot), invalid input isolation, retry success, retry after a
lost acknowledgement, retry exhaustion, non-retryable business failure, reversal, log masking, CSV parsing and a
50-thread concurrent commit test.

## Project layout

```
model/       data types: request, raw record, status lifecycle, per-record result
input/       TransactionSource interface + in-memory and CSV adapters
validation/  pure input validation (never echoes raw values)
store/       TransactionStore interface + in-memory implementation (atomic commit, ledger, idempotency index)
processing/  TransactionProcessor (business logic), retry policy, failure injection, event log, report
app/         command-line entry point and sample data
```
