# SETUP

## Prerequisites
- Docker (with Compose v2) – runs PostgreSQL, and Testcontainers during backend tests
- Java 21 and Maven 3.9+
- Node 22+ and npm

## 1. Credentials
```bash
cp .env.example .env
```
Edit `.env` and replace every `CHANGE_ME…`:
- `POSTGRES_PASSWORD` – any strong password
- `JWT_SECRET` – at least 32 chars: `openssl rand -base64 48`
- `SEED_PASSWORD` – at least 12 chars; the password of the five demo users
`.env` is git-ignored. `docker-compose.yml` and `application.yml` only reference these names.

## 2. Database (Docker image)
```bash
docker compose up -d postgres
docker compose ps          # wait until postgres is "healthy"
```
Flyway creates the schema automatically when the backend starts. To wipe the database: `docker compose down -v`.

## 3. Backend
```bash
cd backend
mvn verify                 # compiles + runs tests (needs Docker running)
mvn spring-boot:run -Dspring-boot.run.profiles=dev # HTTP 8080; Swagger UI at /swagger-ui.html
```
For a host-side Maven run, pass `-Dspring-boot.run.profiles=dev` explicitly (quote the whole argument in PowerShell). Compose passes `SPRING_PROFILES_ACTIVE` to the backend container. The dev profile creates these five users, all with `SEED_PASSWORD`:
`admin@planova.local`, `organizer@planova.local`, `member@planova.local`, `member2@planova.local`, `viewer@planova.local`.
The dev profile also creates the team *Operations Response* (escalation chain: member → member2 → organizer) and the plan *Demo Summer Fest*, so the incident flow works immediately.
Background jobs (escalation every 15 s, recovery 30 s, notification retry 20 s) are controlled by `SCHEDULER_ENABLED` (default true).

## 4. Frontend
```bash
cd frontend
npm install
npm run dev                # http://localhost:5173
npm test                   # 35 tests
npm run build
```

## Everything in containers (optional)
```bash
docker compose --profile app up -d --build     # UI on http://localhost:3000
```

## Verify the database schema without Java or Docker
Needs only the PostgreSQL 16 server binaries (`/usr/lib/postgresql/16/bin`):
```bash
scripts/verify-migrations.sh
```
Creates a throw-away cluster, applies V1–V5, runs 28 constraint assertions, deletes it.
`scripts/verify-incident-sql.sh` does the same for the incident SQL (76 checks incl. concurrent races; needs `python3` + `psycopg`).

## Status
The V1–V5 snapshot compiled and passed 74 Maven tests on the Windows verification machine. If Testcontainers receives HTTP 400 from Docker Desktop, select the Linux engine and set the tested API version in PowerShell:

```powershell
$env:DOCKER_HOST = 'npipe:////./pipe/dockerDesktopLinuxEngine'
mvn '-Dapi.version=1.44' test
```

See `PROGRESS.md` for current verification status.
