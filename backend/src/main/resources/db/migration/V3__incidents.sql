-- V3: incidents and their assignment history.
-- Design goal: let the DATABASE refuse impossible states, so a bug in service code
-- (or a race between two requests/workers) fails loudly instead of corrupting data.

CREATE TABLE incident (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_id          UUID         NOT NULL REFERENCES plan (id),
    event_id         UUID,
    title            VARCHAR(200) NOT NULL,
    description      TEXT         NOT NULL,
    severity         VARCHAR(20)  NOT NULL,
    status           VARCHAR(20)  NOT NULL DEFAULT 'OPEN',
    reporter_id      UUID         NOT NULL REFERENCES app_user (id),
    -- Denormalised copy of the current assignee for fast listing; the source of truth
    -- for history is incident_assignment.
    assignee_id      UUID         REFERENCES app_user (id),
    ack_deadline     TIMESTAMPTZ,
    -- Highest assignment sequence handed out so far (0 = never assigned).
    current_sequence INTEGER      NOT NULL DEFAULT 0,
    -- Client-supplied key so a retried "report incident" request is not stored twice.
    idempotency_key  VARCHAR(100),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    acknowledged_at  TIMESTAMPTZ,
    resolved_at      TIMESTAMPTZ,
    closed_at        TIMESTAMPTZ,
    version          BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_incident_severity CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT ck_incident_status   CHECK (status IN
        ('OPEN', 'ASSIGNED', 'ACKNOWLEDGED', 'IN_PROGRESS', 'RESOLVED', 'CLOSED')),
    CONSTRAINT fk_incident_event_in_plan FOREIGN KEY (event_id, plan_id)
        REFERENCES plan_event (id, plan_id),
    -- Working states always have an owner.
    CONSTRAINT ck_incident_active_has_assignee CHECK (
        status NOT IN ('ASSIGNED', 'ACKNOWLEDGED', 'IN_PROGRESS') OR assignee_id IS NOT NULL),
    -- ASSIGNED means "waiting for acknowledgement", so a deadline must exist.
    CONSTRAINT ck_incident_assigned_has_deadline CHECK (
        status <> 'ASSIGNED' OR ack_deadline IS NOT NULL)
);

-- Retry of the same create request by the same reporter hits this index.
CREATE UNIQUE INDEX ux_incident_idempotency
    ON incident (reporter_id, idempotency_key) WHERE idempotency_key IS NOT NULL;

CREATE INDEX ix_incident_plan_status ON incident (plan_id, status);
CREATE INDEX ix_incident_assignee    ON incident (assignee_id, status);
-- The escalation scheduler scans only incidents still waiting for acknowledgement.
CREATE INDEX ix_incident_ack_due     ON incident (ack_deadline) WHERE status = 'ASSIGNED';

CREATE TABLE incident_assignment (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    incident_id     UUID        NOT NULL REFERENCES incident (id) ON DELETE CASCADE,
    sequence        INTEGER     NOT NULL,
    assignee_id     UUID        NOT NULL REFERENCES app_user (id),
    reason          VARCHAR(20) NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    assigned_by     UUID        REFERENCES app_user (id),   -- NULL = system (automation)
    assigned_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    ack_deadline    TIMESTAMPTZ,
    acknowledged_at TIMESTAMPTZ,
    ended_at        TIMESTAMPTZ,
    CONSTRAINT ck_assignment_sequence CHECK (sequence >= 1),
    CONSTRAINT ck_assignment_reason   CHECK (reason IN ('AUTO', 'ESCALATION', 'MANUAL')),
    CONSTRAINT ck_assignment_status   CHECK (status IN
        ('ACTIVE', 'ACKNOWLEDGED', 'SUPERSEDED', 'COMPLETED')),
    -- Two workers racing to create "assignment #N" for one incident: second one fails.
    -- This is the database-level guard against duplicate escalation.
    CONSTRAINT ux_assignment_incident_sequence UNIQUE (incident_id, sequence)
);

-- Invariant 1 of the brief: at most ONE current (open) assignment per incident.
CREATE UNIQUE INDEX ux_assignment_one_open
    ON incident_assignment (incident_id) WHERE status IN ('ACTIVE', 'ACKNOWLEDGED');

CREATE INDEX ix_assignment_assignee ON incident_assignment (assignee_id, status);
