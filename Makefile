# Without Docker, tests and the app use a throwaway Postgres on port 55433.
DB_ENV = LEDGER_TEST_DB_URL=jdbc:postgresql://127.0.0.1:55433/ledger_test LEDGER_TEST_DB_USER=ledger LEDGER_TEST_DB_PASSWORD=ledger

.PHONY: db-up db-down test run chaos demo-data

N ?= 100000
CONCURRENCY ?= 32
PORT ?= 8095

db-up:
	scripts/local-db.sh start

db-down:
	scripts/local-db.sh stop

test:
	@if command -v docker >/dev/null 2>&1; then ./mvnw -B -ntp verify; \
	else scripts/local-db.sh start && $(DB_ENV) ./mvnw -B -ntp verify; fi

# Port is configurable: make run PORT=9000 (8080 and 8091 are often taken).
run: db-up
	PORT=$(PORT) LEDGER_DB_URL=jdbc:postgresql://127.0.0.1:55433/ledger ./mvnw -B -ntp spring-boot:run -Dspring-boot.run.profiles=demo

# Chaos proof: load, duplicates and a kill -9. Resets the ledger database, app runs on port 8091.
chaos: db-up
	./mvnw -B -ntp -q -DskipTests package
	java scripts/chaos/Chaos.java $(N) $(CONCURRENCY)

# Posts 200 demo transfers to the running app and writes docs/demo/bank-statement.csv
demo-data:
	java scripts/demo/DemoData.java http://localhost:$(PORT)
