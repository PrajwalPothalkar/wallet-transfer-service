# Wallet & P2P Transfer — Design Write-up

## Data model
One table pair in PostgreSQL (Flyway `V1`):

- `wallets`: `id BIGSERIAL PK`, `user_id TEXT NOT NULL UNIQUE` (this is what makes get-or-create race-free), `balance_paige BIGINT NOT NULL CHECK (balance_paige >= 0)` — a negative balance is not representable, `created_at`.
- `transfers`: `id BIGSERIAL PK`, `idempotency_key TEXT NOT NULL UNIQUE`, `from_wallet_id`/`to_wallet_id` FKs to `wallets`, `amount_paise BIGINT NOT NULL CHECK (> 0)`, `status ('completed'|'declined')`, `decline_reason`, timestamps, `CHECK (from <> to)`, indexes on both wallet columns.

All money is integer paise (`BIGINT`). There is no `float`/`BigDecimal`/rupees-decimal anywhere, and deserialization rejects non-integer JSON amounts.

## Simplest-correct mechanism for conservation + no-overdraft
One method, `TransferService.applyMovement`, owns the money movement:

```
lock both wallet rows FOR UPDATE in ascending id order
INSERT idempotency claim ... ON CONFLICT DO NOTHING     (same transaction)
UPDATE wallets SET balance = balance - ? WHERE id = ? AND balance >= ?   -- conditional debit
UPDATE wallets SET balance = balance + ? WHERE id = ?                    -- credit
mark transfer completed / declined                                      COMMIT
```

- **Conditional debit is the authority.** One atomic `UPDATE ... WHERE balance >= amount` returning 0 rows *is* the no-overdraft decision — hard to race, impossible to partially apply. There is no read-then-write of a balance anywhere in app code (the classic lost-update anti-pattern).
- **Sorted lock order kills the deadlock trap.** Every path locks the wallet pair in ascending id order, so an A→B + B→A collision can never form a cycle.
- **Rejected:**
  - App-level read-balance/subtract/write → lost updates under contention; rejected outright.
  - Plain `SELECT ... FOR UPDATE` + balance check in the JVM → two round-trips and a check that's stale the instant it's read; the conditional `UPDATE` collapses check+debit into one statement.
  - `SERIALIZABLE` isolation → provably correct, but every concurrent collision pays a full serialization abort/retry, throughput collapses well before lock contention matters, and the mechanism is obscured rather than auditable. A single conditional UPDATE plus one unique constraint is the minimal correct thing; everything heavier adds cost without adding safety here.

## Where idempotency lives
`idempotency_key` is `UNIQUE` in the DB, and the claim `INSERT ... ON CONFLICT DO NOTHING` executes **in the same transaction** as the debit/credit. That is the whole TOCTOU defense: a concurrent duplicate either waits on the unique index and replays the winner's committed row, or loses the insert and replays too — identical responses, exactly once. Idempotency is decided by the database (holds across instances), never by a separate lookup or app memory. Same key + different body → `409 idempotency_key_reused`.

## Consistency vs. availability
Money → **strong consistency**. One Postgres database is the single source of truth: a transfer is atomic, durable on COMMIT, exactly-once by constraint, and all writes are serialized behind the same row locks. Consciously given up: horizontal write fan-out, multi-region active-active, async/eventual ledgers — each would require an out-of-band reconciliation layer (second ledger, saga, or drift-repair job) to keep conservation, which is strictly more machinery than one correct transaction. Availability is bounded by the single DB; for this service (read-mostly, single tenant) that trade is the right one, and backup/replication is the accepted operational story rather than eventual-consistency code.

## Observability (deploy/operate)
Multi-stage Dockerfile, non-root user, `HEALTHCHECK`; `docker compose up --build` brings app+Postgres up in one command. JSON logs with a per-request `correlation_id` (echoed in `X-Correlation-Id`) log the domain events: `transfer_created`, `wallet_debited`, `wallet_credited`, `transfer_completed`, `transfer_declined_insufficient_funds`, `idempotent_replay_hit`. `/metrics` (also `/actuator/prometheus`) exposes request latency with p50/p95/p99 quantiles and domain counters `wallet_transfers_completed_total`, `wallet_transfers_declined_insufficient_funds_total`, `wallet_idempotent_replays_total`. (Deliberately client-side percentiles, not histogram buckets: in Micrometer 1.14 the Prometheus exporter suppresses the quantile series when buckets publish on the same meter.)

## Free-tier cost note
₹0. GitHub (public repo), Docker Desktop/local Testcontainers Postgres, and Render's free web service + managed Postgres. Everything used in this exercise runs on free tiers.

## AI directed-vs-decided

I directed the design: the two-table model, integer-paise money, PostgreSQL as the
concurrency authority, the conditional-debit + sorted-lock + same-transaction idempotency
primitive, Flyway-migrated schema, and the observability contract (JSON logs with correlation id,
p99 + domain counters). I decided against JPA/Hibernate after review — the correctness
mechanisms here are exact SQL statements, and I wanted them explicit, not ORM-managed.
I kept `@Transactional` on the single service method only.

I let the AI (opencode, Muse Spark) draft and explain: reasoning cross-checks of the
transaction semantics, the JPA-vs-JDBC and jOOQ comparisons (the jOOQ version was a
sketch discussed in chat only — it was not implemented, and the repo stays on
JdbcTemplate), plus doc drafting including this write-up's earlier sections.

What I personally ran and reviewed: `mvn verify` (4/4 integration tests green),
`docker compose up --build` (clean one-command startup), the burst script against the
deployed URL (all probes passing — race-free get-or-create, idempotency storm,
conservation under contention), plus live log and `/metrics` inspection
(correlation ids threading request to decision, p99 quantiles and domain counters
publishing). Everything required is executed and working. Every AI-touched file was
reviewed by me before commit; all commits are human-authored. No claim in this write-up
is AI-generated-but-unverified — each invariant was reproduced against the live URL.