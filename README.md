# Wallet & P2P Transfer Service — Spring Boot

A small, API-only wallet service designed for correctness under concurrent transfer requests. The implementation uses Spring Boot 3, Java 21, Spring JDBC, PostgreSQL, Flyway, Micrometer/Prometheus, JSON logging, Docker Compose, and Testcontainers.

## Why this design

The system does not rely on in-memory locks, Java `synchronized`, or a cache for money correctness. PostgreSQL is the authority:

- `wallets.user_id` is unique, making wallet get-or-create race-safe.
- Balances and amounts are `BIGINT` integer paise, never floats or decimal rupees.
- `transfers.idempotency_key` is unique and is inserted in the **same database transaction** as the debit, credit, and final status.
- Both wallets are locked with `SELECT … FOR UPDATE` in ascending ID order.
- The debit uses `UPDATE … WHERE balance_paise >= amount`, making an overdraft impossible to persist.

## Project layout

```text
src/main/java/com/paytm/exercise/wallet/
  api/             HTTP controllers and JSON DTOs
  config/          application properties and JDBC URL normalisation
  domain/          small immutable business records/enums
  middleware/      correlation ID and demo authentication
  observability/   JSON domain-event logging and Micrometer counters
  repository/      explicit SQL through JdbcTemplate
  service/         transaction/use-case orchestration
  shared/          API error and validation types
```

`TransferService.create()` is intentionally the only owner of the transfer transaction. Repositories do not begin or commit transactions themselves. That makes the critical sequence auditable.

## API

Authentication is intentionally simplified for the exercise:

```text
Authorization: Bearer user:<user-id>
```

| Endpoint | Caller | Purpose |
| --- | --- | --- |
| `POST /wallets` | any valid caller | Get or create that caller’s wallet. |
| `GET /wallets/{id}` | wallet owner | Read current balance. |
| `POST /transfers` | source wallet owner | Transfer with `from`, `to`, `amount_paise`, `idempotency_key`. |
| `GET /transfers/{id}` | either participant | Read final transfer status. |
| `GET /actuator/health` | public | Database-aware health check. |
| `GET /metrics` | public for exercise | Prometheus-format metrics (also at `/actuator/prometheus`). |
| `GET /actuator/prometheus` | public for exercise | Prometheus metrics. |

`POST /wallets` starts a new wallet with `WALLET_INITIAL_BALANCE_PAISE` (default `100000`) so the take-home transfer probe can run. This is controlled test funding, not how a production money system should create value. In production, create zero-balance wallets and fund them through immutable, auditable ledger entries.

## Run locally

The Docker path is the cleanest and includes PostgreSQL:

```bash
docker compose up --build
node scripts/burst.mjs
```

The burst script proves all required live behaviours: 50 concurrent wallet creations; 30 idempotent concurrent retries; 400 contended cross-transfers plus overdraft declines; and observability (correlation id + `/metrics` domain counters + p99 quantile).

If Maven and Postgres are installed locally:

```bash
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/wallet
export SPRING_DATASOURCE_USERNAME=wallet
export SPRING_DATASOURCE_PASSWORD=wallet
mvn spring-boot:run
```

Run the Testcontainers integration test with Docker available:

```bash
mvn verify
```

Example request:

```bash
curl -s -X POST http://localhost:8080/wallets \
  -H 'Authorization: Bearer user:alice'
```

## Transfer transaction

```text
BEGIN
  check committed idempotency result; same body => replay, changed body => 409
  lock source and destination wallet rows in ascending ID order
  check source ownership
  INSERT transfer idempotency key ON CONFLICT DO NOTHING
  conflict after concurrent winner => read/replay stored result or return 409
  insufficient funds => persist declined transfer and COMMIT
  conditional debit source
  credit destination
  persist completed transfer
COMMIT
```

The initial read does not create a TOCTOU risk because the unique insert remains the authoritative idempotency claim. If another transaction inserts the key after the first read, PostgreSQL waits on that unique-index conflict; the losing request then reads the winner’s committed result.

## Observability

- All logs are JSON and contain `correlation_id`; the API returns the same ID in `X-Correlation-Id`.
- Domain events: `wallet_created`, `wallet_get_or_create_replay`, `transfer_created`, `wallet_debited`, `wallet_credited`, `transfer_completed`, `transfer_declined_insufficient_funds`, and `idempotent_replay_hit`.
- `/metrics` (and `/actuator/prometheus`) exports request count, request latency with p50/p95/p99 quantiles, and domain counters:  - `wallet_transfers_completed_total`
  - `wallet_transfers_declined_insufficient_funds_total`
  - `wallet_idempotent_replays_total`
- `/dashboard.html` renders the same numbers as a human-readable live page (no dependencies, polls `/metrics`).

  Note: `http.server.requests` deliberately publishes client-side percentile quantiles rather than histogram buckets. In Micrometer 1.14 the Prometheus exporter suppresses the quantile series when buckets are published on the same meter, so the two are mutually exclusive; a numeric p99 line readable with curl is the better deal for a single-instance exercise (see `HttpLatencyMetricsConfig`).
