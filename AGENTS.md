®# AGENTS.md

Wallet & P2P transfer service (Spring Boot 3, Java 21, PostgreSQL). Read `README.md` first — the "Why this design" and "Transfer transaction" sections are the authoritative spec.

## Money invariants (do not break)

- Amounts/balances are integer paise (`BIGINT`), never floats or `BigDecimal`.
- PostgreSQL is the concurrency authority. No in-memory locks, `synchronized`, or caching for money correctness.
- `TransferService.create()` is the ONLY place with `@Transactional`. Repositories never start/commit transactions; do not add `@Transactional` to repository methods or split debit/credit/idempotency into separate transactions.
- Debits are conditional: `UPDATE ... WHERE balance_paige >= amount`. Wallet pair locks are taken in ascending ID order. The idempotency insert lives in the same transaction as debit/credit.

## Commands

- Full verification (what CI runs in `.github/workflows/verify.yml`):
  `mvn -B verify`, then `docker compose up --build -d && node scripts/burst.mjs`
- `mvn verify` runs the Testcontainers integration test `src/test/java/com/paytm/exercise/wallet/TransferConcurrencyIT.java` (4 tests: race-free get-or-create, idempotency storm, key-reuse conflict, bidirectional contention). `mvn test` does NOT run `*IT` tests; use `verify`.
- The IT needs a Docker socket the HTTP client can reach. On this Mac, Docker Desktop 4.90's socket rejects HMAC requests whose `Host` header is empty — which Testcontainers' docker-java client sends — so local `mvn verify` may SKIP the IT (`Tests run: 4, Skipped: 4`). Workaround: point it at a reachable daemon (`DOCKER_HOST=unix://$HOME/.docker/run/docker.sock`); a native Linux/CI socket works without any of this.
- Local run against system Postgres needs these env vars first: `SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/wallet`, `SPRING_DATASOURCE_USERNAME=wallet`, `SPRING_DATASOURCE_PASSWORD=wallet`, then `mvn spring-boot:run`.

## Wiring / quirks

- Jackson is globally `SNAKE_CASE` (`spring.jackson.property-naming-strategy`) — DTO records like `amountPaise` map to `amount_paise` JSON.
- Auth is demo-only: `Authorization: Bearer user:<user-id>`; ownership is enforced server-side.
- `config/DataSourceConfig.java` auto-prefixes `jdbc:` onto `postgresql://` URLs (Render's format); plain `jdbc:` URLs also work.
- All logs are JSON (Logstash encoder) with `correlation_id`, echoed in the `X-Correlation-Id` response header.
- Metrics: `/metrics` (`MetricsController`) and `/actuator/prometheus` serve the same Prometheus scrape. `http.server.requests` publishes client-side p50/p95/p99 `quantile=` series only — in Micrometer 1.14 the Prometheus exporter suppresses quantiles when buckets are published on the same meter, so do NOT re-enable `percentiles-histogram` for it or the burst's p99 check fails.
- Schema is Flyway migrations in `src/main/resources/db/migration` — additive/versioned only (README describes the pattern).
- Wallets are seeded with `WALLET_INITIAL_BALANCE_PAISE` (default `100000`) purely so the burst probe can fund transfers.