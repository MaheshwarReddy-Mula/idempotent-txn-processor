Design Note: Idempotent Background Transaction Processor
1. How I read the problem
Upstream systems send transactions at-least-once. That means the same transaction can show up twice, records can arrive in the wrong order, some are malformed, and sometimes a downstream call fails halfway. The job of this service is simple to say and tricky to get right: every real transaction must change a balance exactly once, and I should be able to explain what happened to every single record.
I went for the L2 version of the exercise. I tried to get the core behaviour right first (no double posting, correct order, safe retries) and kept everything else small and easy to swap.
2. Overall structure
The code is split so the business logic never knows where data comes from or where it is stored.
Input: a TransactionSource interface with two implementations, an in-memory list and a CSV reader. A queue or API reader would plug in the same way.
Validation: a separate class that checks one raw record and returns every problem it finds, not just the first.
Processor: the part that decides what to do with a record (reject it, mark it duplicate, hold it, or process it).
Store: a TransactionStore interface that holds transaction state, the ledger and balances. I used an in-memory version, but the processor only talks to the interface, so a database version could replace it.
Log and report: structured log lines while it runs, and a summary at the end.
Records come in as raw strings on purpose. If parsing happened inside the source, one bad line could throw an exception and kill the whole batch. This way a bad line just becomes a REJECTED result and the batch carries on.
3. Statuses
I used the statuses from the brief (RECEIVED, PROCESSING, PROCESSED, DUPLICATE, FAILED, RETRY_PENDING) and added two:
PENDING_SEQUENCE: the record arrived early and is waiting for earlier records.
REJECTED: the record was invalid or conflicted with an earlier one, so it was never processed.
I added REJECTED so that "bad input" and "failed while processing" don't get mixed up in the report. Every input record ends with exactly one outcome, so the counts in the summary always add up to the number of records read. Each transaction also keeps its full status history, for example RECEIVED -> PROCESSING -> RETRY_PENDING -> PROCESSING -> PROCESSED.
4. Idempotency
I protect against double processing in two places, because I didn't want to rely on a single check.
First check, at the front door. The processor remembers every requestId and transactionId it has seen. If a record repeats one of them, I compare a fingerprint of the business data (account, type, amount, currency, sequence, reference). The requestId is left out of the fingerprint because a retrying client may send a new one.
Same fingerprint: it is a DUPLICATE and nothing is executed.
Different fingerprint with the same transaction id: it is REJECTED as a conflict. Quietly accepting a changed amount under the same id would be dangerous.
A requestId reused for a different transaction is also REJECTED.
Second check, where money moves. The store's commit method looks at the ledger first. If that transaction id already has an entry, it does nothing and returns ALREADY_APPLIED. This is the real safety net. Even if the first check were skipped, for example after a crash or with a second consumer, the balance still can't change twice. In a database this would be a unique constraint on the transaction id in the ledger table.
commit also works out the full effect and checks every rule (funds, reversal rules) before it changes anything. Only then does it update the balance, the ledger and the reversal marker together. So a failure can't leave a half-finished transaction behind.
5. Duplicates
A duplicate is caught whenever it arrives: after the original was processed, while the original is still waiting in the buffer, or after the original failed. A duplicate of a failed transaction is not reprocessed. If someone wants to re-run it, that should be a deliberate action, not something that happens by accident. The requestId of the duplicate is remembered too, so a later replay of that same key is spotted straight away.
6. Out-of-order events: sequence-based buffering
I chose Option A. Records are applied strictly in sequence order. If a record's sequence number is ahead of the next expected one, it is held as PENDING_SEQUENCE and released as soon as everything before it is resolved.
The sample data shows why this matters. TXN-2002 is a debit of 40 and it arrives before TXN-2001, the credit of 100. If I processed it straight away the account would be overdrawn. Waiting for TXN-2001 gives the correct result of 100 - 40 + 25 = 85.
A few rules I had to decide:
A sequence slot counts as resolved when its record is PROCESSED, ends as FAILED, or was rejected as invalid (as long as its sequence number could be read). Otherwise one bad record would block every record after it.
Anything still waiting at the end is not hidden. The report lists the missing sequence numbers.
The sample data uses one running sequence across different accounts (TXN-2004 is for ACC-202 and TXN-2005 for ACC-203, both continuing the count), so I used one global sequence. In production I would use one sequence per partition key such as account, so accounts don't wait on each other.
Trade-offs I am aware of: a sequence number that never arrives blocks everything behind it, and right now there is no timeout for that. A terminally failed record also uses up its slot instead of stopping the stream. Whether a failure should halt the stream is really a business decision.
7. Retries and failures
Failures fall into three groups:
Transient failures are retried, up to 3 attempts by default, with a wait that doubles each time. Each retry shows up in the status history as RETRY_PENDING.
Business failures such as insufficient funds, or reversing something that was already reversed, are not retried because retrying can't fix them. The record goes to FAILED straight away.
Anything unexpected goes to FAILED with a generic reason, and only the exception type is logged because messages can contain sensitive data.
If retries run out, the record is FAILED with the reason RETRIES_EXHAUSTED and the attempt count.
To test retry safety I simulate failures at two points. One is before the commit, where nothing was applied and the retry applies it once. The other is after the commit, where the money did move but the acknowledgement was lost. That second case is the one naive retry code gets wrong, because the retry would post the transaction again. Here the retry reaches the idempotent commit, gets ALREADY_APPLIED back, and the balance stays correct.
I also added REVERSAL support. A reversal points to an earlier processed transaction and undoes it once. A second reversal of the same transaction fails with ALREADY_REVERSED.
8. Logging, report and sensitive data
While running, the service writes one key=value line per event (received, pending, processing, retry scheduled, processed, duplicate, rejected, failed) with timestamp, transaction id, request id and attempt number, which is easy to search in a log tool.
At the end it prints a report with counts by status, a line per input record, the status history of each transaction, retries, duplicates, missing sequence numbers and final balances.
On sensitive data: account ids are masked in logs and the report (only the last three characters are shown), amounts are never logged, raw input values are cleaned before logging, and validation messages never repeat what was in the input. Ids are limited to a safe format.
9. How I used AI
I used an AI assistant (Claude) heavily on this. It generated the first version of the project structure, the processor, store and validator, and the test suite from the problem statement. I want to be clear about that rather than hide it.
What I did with the output:
I went through the design against the brief and I am the one explaining the main choices: sequence-based ordering, idempotency at both the request level and the commit level, releasing a sequence slot when a record is invalid, and simulating the lost-acknowledgement case.
The code was compiled and the 23 unit tests were run during development, using a lightweight test runner because Maven was not available in that environment. The two sample CSV files were run end to end and I checked the balances by hand (for ACC-201: 100 - 40 + 25 = 85).
I also tried the failure paths on purpose: retries running out, a lost acknowledgement, a conflicting duplicate, and many threads committing the same transaction at once.
Things that were wrong or had to be corrected:
The first version of the edge-case input file stalled the sequence stream. A malformed line has no readable sequence number, so its slot was never released and every later record stayed pending. I fixed the data so only one deliberate gap (sequence 7) remains, and noted in the file that it is on purpose.
The sample data runs one sequence across different accounts, so I changed to a single global sequence instead of per-account sequences, and noted above that production would partition it.
10. Known limitations
State is in memory only. After a restart the next expected sequence and the waiting records are lost.
Retries happen inline with a sleep. A real system would use a delayed retry queue.
Records are handled one at a time per stream. The store itself is thread-safe.
A missing sequence number blocks later records, with no timeout yet.
The CSV reader does not support quoted fields.
11. What I would do before production
Persistence: a database store with a unique constraint on the ledger transaction id and an idempotency table with an expiry, and save the next sequence and the waiting records so a restart can resume.
Concurrency: one consumer per partition (for example per account), and row locks or versioning on balances if several workers are used.
Queue handling: at-least-once delivery with this idempotent consumer, acknowledge messages only after the result is saved, and send FAILED and REJECTED records to a dead-letter queue.
Ordering: a sequence per key, a timeout and alert for missing numbers, and a limit on the buffer size.
Retries: a delayed retry queue with jitter instead of sleeping in the process.
Monitoring: counters for processed, duplicate, failed and retried records, buffer depth, the age of the oldest waiting record, and alerts on stuck sequences and dead-letter growth.
Recovery: an admin action to replay a failed transaction, and a reconciliation job that checks the ledger against balances.
Testing: randomised tests that shuffle order and add duplicates and check the final balances never change, plus tests that crash between commit and acknowledgement.
final state), load/soak tests, chaos tests for crashes between commit and acknowledgement. |
