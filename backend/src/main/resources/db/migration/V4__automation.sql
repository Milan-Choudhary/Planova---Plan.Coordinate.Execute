-- V4: workflow execution, notifications, audit trail.

CREATE TABLE workflow_definition (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR(120) NOT NULL,
    version    INTEGER      NOT NULL,
    status     VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    -- Typed configuration (severity -> ack timeout, max escalation level, ...).
    -- Validated by the backend before a definition may become ACTIVE.
    definition JSONB        NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_workflow_definition_status CHECK (status IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    CONSTRAINT ux_workflow_definition_version UNIQUE (name, version)
);

-- At most one ACTIVE version per workflow name.
CREATE UNIQUE INDEX ux_workflow_definition_active
    ON workflow_definition (name) WHERE status = 'ACTIVE';

CREATE TABLE workflow_execution (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    incident_id   UUID        NOT NULL REFERENCES incident (id) ON DELETE CASCADE,
    -- Pins the execution to the definition version it started with (edge case:
    -- "definition changes while an execution is running").
    definition_id UUID        NOT NULL REFERENCES workflow_definition (id),
    status        VARCHAR(20) NOT NULL DEFAULT 'RUNNING',
    attempts      INTEGER     NOT NULL DEFAULT 0,
    max_attempts  INTEGER     NOT NULL DEFAULT 3,
    last_error    TEXT,
    started_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at   TIMESTAMPTZ,
    version       BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT ck_workflow_execution_status CHECK (status IN
        ('RUNNING', 'WAITING_ACK', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_workflow_execution_attempts CHECK (attempts >= 0 AND attempts <= max_attempts),
    -- One execution per incident: a duplicated INCIDENT_CREATED event cannot start a second.
    CONSTRAINT ux_workflow_execution_incident UNIQUE (incident_id)
);

CREATE TABLE notification (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    recipient_id UUID         NOT NULL REFERENCES app_user (id),
    incident_id  UUID         REFERENCES incident (id) ON DELETE CASCADE,
    type         VARCHAR(40)  NOT NULL,
    channel      VARCHAR(20)  NOT NULL DEFAULT 'IN_APP',
    status       VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    title        VARCHAR(200) NOT NULL,
    body         TEXT,
    attempts     INTEGER      NOT NULL DEFAULT 0,
    last_error   TEXT,
    -- Deterministic key such as "INCIDENT_ASSIGNED:<incidentId>:<sequence>".
    -- A retried or duplicated handler computes the same key and is rejected here.
    dedupe_key   VARCHAR(200) NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent_at      TIMESTAMPTZ,
    read_at      TIMESTAMPTZ,
    CONSTRAINT ck_notification_channel CHECK (channel IN ('IN_APP', 'EMAIL')),
    CONSTRAINT ck_notification_status  CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    CONSTRAINT ck_notification_attempts CHECK (attempts >= 0),
    CONSTRAINT ux_notification_dedupe UNIQUE (recipient_id, channel, dedupe_key)
);

CREATE INDEX ix_notification_inbox  ON notification (recipient_id, created_at DESC);
CREATE INDEX ix_notification_unread ON notification (recipient_id) WHERE read_at IS NULL;
CREATE INDEX ix_notification_retry  ON notification (status, attempts) WHERE status <> 'SENT';

CREATE TABLE audit_record (
    id             BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_id       UUID,        -- NULL = system. No FK on purpose: audit rows must outlive users.
    action         VARCHAR(60)  NOT NULL,
    entity_type    VARCHAR(40)  NOT NULL,
    entity_id      UUID,
    plan_id        UUID,
    before_state   JSONB,
    after_state    JSONB,
    correlation_id VARCHAR(64),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ix_audit_entity ON audit_record (entity_type, entity_id, created_at DESC);
CREATE INDEX ix_audit_plan   ON audit_record (plan_id, created_at DESC);
CREATE INDEX ix_audit_time   ON audit_record (created_at DESC);

-- Tamper RESISTANCE, not tamper-proofing: the application cannot rewrite history, but a
-- database superuser can still drop this trigger. Documented honestly in DECISIONS.md.
CREATE FUNCTION audit_record_block_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_record is append-only (% blocked)', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_record_no_update_delete
    BEFORE UPDATE OR DELETE ON audit_record
    FOR EACH ROW EXECUTE FUNCTION audit_record_block_change();

CREATE TRIGGER trg_audit_record_no_truncate
    BEFORE TRUNCATE ON audit_record
    FOR EACH STATEMENT EXECUTE FUNCTION audit_record_block_change();
