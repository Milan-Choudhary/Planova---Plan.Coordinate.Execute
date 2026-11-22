#!/usr/bin/env bash
# Shared helper (source it): starts a THROWAWAY PostgreSQL cluster on a spare port, applies every
# Flyway migration, and removes everything on exit. Needs only the PostgreSQL server binaries -
# no Docker, no Maven. It never touches your real database.
#
# After `start_temp_pg` these are set:  PG_WORK (socket dir)  PG_PORT  PG_DB  (user is "verify", trust auth)

PG_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PG_BIN="${PG_BIN:-$(ls -d /usr/lib/postgresql/*/bin 2>/dev/null | sort -V | tail -1)}"
PG_PORT="${VERIFY_PG_PORT:-55432}"
PG_DB="planova_verify"
PG_WORK=""
PG_RUN_AS=()

pg_run() { if [ ${#PG_RUN_AS[@]} -gt 0 ]; then "${PG_RUN_AS[@]}" "$*"; else bash -c "$*"; fi; }

pg_cleanup() {
  [ -n "$PG_WORK" ] || return 0
  pg_run "$PG_BIN/pg_ctl -D $PG_WORK/data stop -m immediate >/dev/null 2>&1 || true"
  rm -rf "$PG_WORK"
}

start_temp_pg() {
  PG_WORK="$(mktemp -d)"
  # PostgreSQL refuses to run as root; fall back to an unprivileged user in that case.
  if [ "$(id -u)" -eq 0 ]; then PG_RUN_AS=(su -s /bin/bash nobody -c); chown nobody "$PG_WORK"; fi
  trap pg_cleanup EXIT
  echo ">> temp PostgreSQL from $PG_BIN on port $PG_PORT"
  pg_run "$PG_BIN/initdb -D $PG_WORK/data -U verify --auth=trust >/dev/null"
  pg_run "$PG_BIN/pg_ctl -D $PG_WORK/data -o '-p $PG_PORT -k $PG_WORK -c listen_addresses= -c max_connections=100' -l $PG_WORK/log -w start >/dev/null"
  pg_run "psql -h $PG_WORK -p $PG_PORT -U verify -q -d postgres -c 'CREATE DATABASE $PG_DB'"
}

apply_migrations() {
  local f
  for f in $(ls "$PG_ROOT"/backend/src/main/resources/db/migration/V*.sql | sort -V); do
    echo ">> applying $(basename "$f")"
    pg_run "psql -h $PG_WORK -p $PG_PORT -U verify -v ON_ERROR_STOP=1 -q -d $PG_DB -f '$f'"
  done
}
