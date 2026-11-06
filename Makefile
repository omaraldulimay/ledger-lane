# Without Docker, tests and the app use a throwaway Postgres on port 55433.
DB_ENV = LEDGER_TEST_DB_URL=jdbc:postgresql://127.0.0.1:55433/ledger_test LEDGER_TEST_DB_USER=ledger LEDGER_TEST_DB_PASSWORD=ledger

.PHONY: db-up db-down test run

db-up:
	scripts/local-db.sh start

db-down:
	scripts/local-db.sh stop

test:
	@if command -v docker >/dev/null 2>&1; then ./mvnw verify; \
	else scripts/local-db.sh start && $(DB_ENV) ./mvnw verify; fi

run: db-up
	LEDGER_DB_URL=jdbc:postgresql://127.0.0.1:55433/ledger ./mvnw spring-boot:run -Dspring-boot.run.profiles=demo
