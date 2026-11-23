-- V2: plans, events, tasks.

CREATE TABLE plan (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    title       VARCHAR(200) NOT NULL,
    description TEXT,
    owner_id    UUID         NOT NULL REFERENCES app_user (id),
    -- The team whose responders handle incidents raised against this plan.
    team_id     UUID         REFERENCES team (id),
    start_date  DATE,
    end_date    DATE,
    status      VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    visibility  VARCHAR(20)  NOT NULL DEFAULT 'PRIVATE',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_plan_status     CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_plan_visibility CHECK (visibility IN ('PRIVATE', 'TEAM')),
    CONSTRAINT ck_plan_dates      CHECK (start_date IS NULL OR end_date IS NULL OR end_date >= start_date)
);

CREATE INDEX ix_plan_owner ON plan (owner_id);
CREATE INDEX ix_plan_team  ON plan (team_id);

-- Per-plan access list. Every plan-scoped endpoint authorizes through this table,
-- which is what stops a user guessing another plan's ID.
CREATE TABLE plan_member (
    plan_id   UUID        NOT NULL REFERENCES plan (id) ON DELETE CASCADE,
    user_id   UUID        NOT NULL REFERENCES app_user (id),
    plan_role VARCHAR(20) NOT NULL,
    added_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (plan_id, user_id),
    CONSTRAINT ck_plan_member_role CHECK (plan_role IN ('OWNER', 'EDITOR', 'VIEWER'))
);

CREATE INDEX ix_plan_member_user ON plan_member (user_id);

CREATE TABLE plan_event (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_id     UUID         NOT NULL REFERENCES plan (id) ON DELETE CASCADE,
    title       VARCHAR(200) NOT NULL,
    description TEXT,
    start_at    TIMESTAMPTZ  NOT NULL,
    end_at      TIMESTAMPTZ  NOT NULL,
    location    VARCHAR(200),
    category    VARCHAR(60),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_plan_event_times CHECK (end_at > start_at),
    -- Lets child tables prove "this event belongs to this plan" with a composite FK.
    CONSTRAINT ux_plan_event_id_plan UNIQUE (id, plan_id)
);

CREATE INDEX ix_plan_event_plan_start ON plan_event (plan_id, start_at);

CREATE TABLE task (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_id     UUID         NOT NULL REFERENCES plan (id) ON DELETE CASCADE,
    event_id    UUID,
    title       VARCHAR(200) NOT NULL,
    description TEXT,
    assignee_id UUID         REFERENCES app_user (id),
    priority    VARCHAR(20)  NOT NULL DEFAULT 'MEDIUM',
    status      VARCHAR(20)  NOT NULL DEFAULT 'TODO',
    deadline    TIMESTAMPTZ,
    created_by  UUID         NOT NULL REFERENCES app_user (id),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_task_priority CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT ck_task_status   CHECK (status IN ('TODO', 'IN_PROGRESS', 'DONE', 'CANCELLED')),
    -- A task may only point at an event of its own plan. (NULL event_id is not checked.)
    CONSTRAINT fk_task_event_in_plan FOREIGN KEY (event_id, plan_id)
        REFERENCES plan_event (id, plan_id)
);

CREATE INDEX ix_task_plan          ON task (plan_id);
CREATE INDEX ix_task_assignee      ON task (assignee_id, status);
CREATE INDEX ix_task_open_deadline ON task (deadline) WHERE status IN ('TODO', 'IN_PROGRESS');
