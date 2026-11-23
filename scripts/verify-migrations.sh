#!/usr/bin/env bash
# Applies every Flyway migration to a throw-away PostgreSQL and runs scripts/schema-assertions.sql.
# Usage: scripts/verify-migrations.sh
set -euo pipefail
source "$(dirname "$0")/lib-pgtemp.sh"

start_temp_pg
apply_migrations

echo ">> running schema assertions"
# stdout (empty result tables) is discarded; PASS/FAIL lines arrive on stderr as NOTICEs.
pg_run "psql -h $PG_WORK -p $PG_PORT -U verify -v ON_ERROR_STOP=1 -q -d $PG_DB -f '$PG_ROOT/scripts/schema-assertions.sql' >/dev/null" 2>&1 \
  | sed -e 's/^psql:[^ ]* //' -e 's/^NOTICE:  //'
