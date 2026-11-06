# Ledger Lane

A double entry ledger service for a marketplace: it records every money movement between customer, platform and
partner brands so that each cent can be explained. Java 21, Spring Boot, plain SQL on PostgreSQL. See `docs/architecture.md`.

## Invariants

1. Every journal entry has at least two postings that sum to exactly 0.
2. Amounts are integer cents with a currency, never floating point.
3. Entries and postings are immutable (enforced by database triggers). Mistakes are fixed with a reversal entry.
4. Every write carries an `Idempotency-Key`. Same key and body replays the original response, same key and a different body is a 409.
5. Accounts flagged as not negative reject any posting that would push their normal balance below zero (422).
6. The sum of all postings is 0 and every account balance equals the sum of its postings. The tests check this after every scenario.

## Run

Needs Java 21. Without Docker, `make` uses a throwaway Postgres 16 on port 55433 (binaries from `/Library/PostgreSQL/16/bin`, data in `.local/pg`).

    make test      # starts the local database if Docker is missing, then ./mvnw verify
    make run       # demo profile on http://localhost:8080 (seeds accounts for brands b1 and b2)
    make chaos     # 100k transfers with duplicates and a kill -9, report in docs/chaos (N=... CONCURRENCY=...)
    make db-down   # stop the local database

With Docker, `./mvnw verify` starts Postgres through Testcontainers. Otherwise set `LEDGER_TEST_DB_URL`,
`LEDGER_TEST_DB_USER` and `LEDGER_TEST_DB_PASSWORD`. API docs: `/swagger-ui.html`, metrics: `/actuator/prometheus`.

## Example

A customer pays 100,00 EUR for a product of brand b1, with 15 % commission:

    curl -i -X POST localhost:8080/v1/transfers \
      -H 'Content-Type: application/json' -H 'Idempotency-Key: order-1001' \
      -d '{"description":"order 1001","postings":[
            {"accountId":"cash:bank","amount":10000,"currency":"EUR"},
            {"accountId":"brand:b1:payable","amount":-8500,"currency":"EUR"},
            {"accountId":"platform:revenue","amount":-1500,"currency":"EUR"}]}'

Sending the same request again returns `200` with the same body and `Idempotent-Replayed: true`.
Positive amounts are debits, negative amounts are credits.
