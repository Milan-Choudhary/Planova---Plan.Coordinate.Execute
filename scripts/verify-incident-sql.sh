#!/usr/bin/env bash
# Runs the REAL incident SQL (parsed from IncidentSql.java) against a throw-away PostgreSQL,
# including concurrent-connection race tests. Needs: PostgreSQL 16 server binaries, python3,
# and `pip install "psycopg[binary]"`.
# Usage: scripts/verify-incident-sql.sh
set -euo pipefail
source "$(dirname "$0")/lib-pgtemp.sh"

start_temp_pg
apply_migrations

echo ">> incident SQL contract + concurrency tests"
python3 -I "$PG_ROOT/scripts/verify_incident_sql.py" \
  "$PG_ROOT/backend/src/main/java/com/planova/incident/IncidentSql.java" \
  "host=$PG_WORK port=$PG_PORT user=verify dbname=$PG_DB"
