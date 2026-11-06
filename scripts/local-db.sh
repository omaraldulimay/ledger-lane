#!/usr/bin/env bash
# Throwaway Postgres for local runs and tests (no Docker needed).
# Uses its own cluster on port 55433 and never touches the system Postgres on 5432.
set -euo pipefail

PG_BIN=/Library/PostgreSQL/16/bin
PORT=55433
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DATA="$ROOT/.local/pg"

case "${1:-}" in
  start)
    if [ ! -d "$DATA" ]; then
      mkdir -p "$DATA"
      "$PG_BIN/initdb" -D "$DATA" -U ledger -A trust -E UTF8 >/dev/null
    fi
    if ! "$PG_BIN/pg_ctl" -D "$DATA" status >/dev/null 2>&1; then
      "$PG_BIN/pg_ctl" -D "$DATA" -l "$ROOT/.local/pg.log" -w \
        -o "-p $PORT -c listen_addresses=127.0.0.1 -c unix_socket_directories=$ROOT/.local" start >/dev/null
    fi
    for db in ledger ledger_test; do
      "$PG_BIN/psql" -h 127.0.0.1 -p $PORT -U ledger -d postgres -tAc \
        "SELECT 1 FROM pg_database WHERE datname='$db'" | grep -q 1 \
        || "$PG_BIN/createdb" -h 127.0.0.1 -p $PORT -U ledger "$db"
    done
    echo "postgres ready on 127.0.0.1:$PORT (dbs ledger and ledger_test, user ledger)"
    ;;
  stop)
    if [ -d "$DATA" ]; then "$PG_BIN/pg_ctl" -D "$DATA" -m fast stop >/dev/null 2>&1 || true; fi
    echo "stopped"
    ;;
  status)
    "$PG_BIN/pg_ctl" -D "$DATA" status 2>&1 | head -1 || true
    ;;
  *)
    echo "usage: $0 start|stop|status" >&2
    exit 1
    ;;
esac
