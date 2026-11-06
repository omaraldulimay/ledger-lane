# Ledger Lane: architecture

## Scenario

Nordkai Marketplace sells products of partner brands. A customer pays Nordkai, Nordkai keeps a
commission, owes the rest to the brand, pays it out weekly, and sometimes refunds the customer.
Ledger Lane is the service that records every one of these money movements so that, at any time,
every cent can be explained and the books balance.

## Core rules (the invariants)

1. **Double entry.** Every journal entry has at least two postings, and the postings sum to exactly 0.
2. **Integer cents.** Amounts are `long` minor units with a currency (EUR only for now). No floating point anywhere.
3. **Immutable.** Entries are never updated or deleted. A mistake is corrected with a reversal entry.
4. **Idempotent writes.** Every write takes an `Idempotency-Key`. Same key + same body returns the original
   result; same key + different body returns `409 Conflict`.
5. **No overdraft** on accounts that must not go negative (brand payable accounts, payouts).
6. **Global check.** The sum of all postings in the system is always 0, and each account balance equals
   the sum of its postings.

## Accounts

| Account | Type | May go negative |
|---|---|---|
| `cash:bank` | asset | no |
| `customer:clearing` | liability | yes |
| `brand:{id}:payable` | liability | no |
| `platform:revenue` | revenue | no |
| `payouts:in-transit` | liability | no |

Example: a customer pays 100,00 EUR for a brand product with 15 % commission:
`cash:bank +10000`, `brand:b1:payable -8500`, `platform:revenue -1500` (credit-normal accounts use negative signs; postings sum to 0).

`payouts:in-transit` is a liability: it holds money we released to the bank but that has not settled yet.
A payout debits `brand:{id}:payable` (we owe the brand less) and credits `payouts:in-transit` (we now owe the bank run).

Sign convention: a posting amount is positive for a debit and negative for a credit. Assets are
debit-normal, liabilities and revenue are credit-normal. The API shows a **normal balance**
(`balance` for debit-normal, `-balance` for credit-normal), and "may not go negative" applies to that
normal balance.

## API (v1, OpenAPI documented)

- `POST /v1/accounts` create an account
- `POST /v1/transfers` post a journal entry (header `Idempotency-Key` required)
- `POST /v1/transfers/{id}/reversal` reverse an entry
- `GET  /v1/accounts/{id}` balance and version
- `GET  /v1/accounts/{id}/postings?cursor=` cursor-paginated history
- `POST /v1/payouts` move a brand's payable balance to `payouts:in-transit`
- `POST /v1/reconciliations` match a bank statement CSV against the ledger (transfers and payouts carry an optional `reference`)
- `GET  /v1/dashboard` read model for the Leitstand (`/` serves the static page)

Errors use RFC 9457 problem+json.

## Consistency

- One database transaction per journal entry.
- Account rows are locked with `SELECT ... FOR UPDATE` in ascending account id order, so concurrent
  entries never deadlock. The balance is stored on the account row and updated in the same transaction
  as the postings, which makes reads cheap; the global check (rule 6) proves the two never drift.
- Idempotency keys live in their own table with a unique constraint, written in the same transaction.

## Events (step 3)

Transactional outbox: the entry and an `outbox` row are written in one transaction; a relay publishes
outbox rows to Kafka (`ledger.entries`, key = entry id) at least once, consumers dedupe by entry id. The relay
claims rows with `FOR UPDATE SKIP LOCKED`, so several relays never publish the same row concurrently. Kafka runs in
CI via Testcontainers; locally the relay logs instead (`ledger.outbox.publisher=log`, the default).

## Tech

Java 21, Spring Boot 4 (Jackson 3), Spring JDBC (`JdbcClient`, plain SQL, no JPA), Flyway, PostgreSQL 16, Maven
wrapper, JUnit 5, Testcontainers (CI), springdoc OpenAPI, Micrometer + Prometheus.

Local run without Docker: a throwaway Postgres on port **55433** from `/Library/PostgreSQL/16/bin`
(never the system Postgres on 5432). Tests use Testcontainers when Docker is available, otherwise
`LEDGER_TEST_DB_URL`.

## Roadmap

| Step | Content | Proof |
|---|---|---|
| 1 | Skeleton, schema, accounts, transfers, idempotency, reversal, balances, tests | Unit + integration tests green |
| 2 | Concurrency and chaos proof: 100k transfers in parallel, duplicates, killed process | Invariants hold, sum = 0 to the cent |
| 3 | Outbox + Kafka events, payouts | Every entry published at least once, exactly once after dedupe by entry id |
| 4 | Reconciliation against a bank statement CSV, small web Leitstand | Mismatches found and explained |
| 5 | CI (build, tests, Trivy, SBOM), ADRs, README, teaser video, portfolio page | CI green, page live |
