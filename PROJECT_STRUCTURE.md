# Planova AI project structure

Planova AI helps organizers plan events and assign tasks, and helps responders handle incidents during an event. The React frontend calls the Spring Boot API. The backend stores plans, tasks, incidents, and their history in PostgreSQL.

## Repository map

```text
planova-ai/
├── backend/
│   ├── pom.xml                              Maven dependencies and Java 21 build
│   ├── src/main/java/com/planova/            Spring Boot entry point and feature modules
│   ├── src/main/resources/
│   │   ├── application.yml                 Spring configuration
│   │   └── db/migration/                   Flyway migrations V1–V5
│   └── src/test/java/com/planova/            Unit and PostgreSQL integration tests
├── frontend/
│   ├── package.json                        npm scripts and dependencies
│   ├── vite.config.ts                      Dev server, API proxy, and test settings
│   └── src/
│       ├── main.tsx and App.tsx             App entry point and routes
│       ├── api/                             HTTP client, API types, and loading hook
│       ├── auth/                            Session state and protected routes
│       ├── components/                      Shared navigation, badges, and loading states
│       ├── pages/                           Dashboards, plans, incidents, tasks, and admin screens
│       ├── test/                            Shared frontend test utilities
│       └── styles.css                       Theme and responsive layouts
├── docs/                                   Requirements, architecture, data model, and test plan
├── scripts/                                PostgreSQL migration and incident SQL verification
├── docker-compose.yml                       Local PostgreSQL and optional app containers
├── .env.example                             Configuration template with placeholders
└── README.md                                Repository entry point
```

## Backend modules

All packages are under [`backend/src/main/java/com/planova/`](backend/src/main/java/com/planova/).

| Package | Responsibility |
| --- | --- |
| `identity`, `access` | Accounts, sign-in, demo seeding, and plan-level permissions |
| `planning` | Plans, plan membership, events, tasks, and responder teams |
| `incident` | Reporting, severity rules, assignments, acknowledgements, escalation, and resolution |
| `notification`, `audit` | In-app delivery and the record of who changed what |
| `dashboard`, `admin` | Summary data and administrative endpoints |
| `config`, `common` | Spring configuration, security setup, and shared API utilities |

## Trace an incident end to end

1. The organizer submits the report form in [`PlanDetailPage.tsx`](frontend/src/pages/PlanDetailPage.tsx) through the shared [API client](frontend/src/api/client.ts).
2. [`IncidentController`](backend/src/main/java/com/planova/incident/IncidentController.java) passes the request to [`IncidentService`](backend/src/main/java/com/planova/incident/IncidentService.java), which checks access, evaluates severity, and records the incident.
3. The incident workflow assigns an eligible responder after the report commits. Look in the [incident package](backend/src/main/java/com/planova/incident/) for assignment, escalation, scheduled jobs, and the critical SQL.
4. The responder opens the in-app notification and acts on the incident. [`IncidentDetailPage.tsx`](frontend/src/pages/IncidentDetailPage.tsx) displays the actions allowed by the API, assignment history, and audit timeline.

## Where to start

| If you want to… | Open |
| --- | --- |
| Run the app locally | [SETUP.md](SETUP.md) |
| See what has been implemented and verified | [PROGRESS.md](PROGRESS.md) |
| Change a request or response | [API_CONTRACTS.md](API_CONTRACTS.md) and [frontend API types](frontend/src/api/types.ts) |
| Understand the database or incident states | [Data model](docs/DATA_MODEL.md) and [State machines](docs/STATE_MACHINES.md) |
| Understand past design choices | [DECISIONS.md](DECISIONS.md) |
| Run or add checks | [Test plan](docs/TEST_PLAN.md) |

Copy `.env.example` to a local `.env` for development. Keep `.env`, IDE metadata, dependencies, and build output out of Git.
