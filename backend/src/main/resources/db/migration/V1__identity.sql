-- V1: identity, teams and memberships.
-- Conventions for ALL migrations:
--   * UUID primary keys (gen_random_uuid() is built into PostgreSQL 13+, no extension needed)
--   * TIMESTAMPTZ everywhere (stored in UTC; the UI converts to the viewer's local time)
--   * "version" column = optimistic-lock counter used by JPA @Version
--   * enum-like columns are VARCHAR + CHECK (easier to evolve than native PG enums)

CREATE TABLE app_user (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    email         VARCHAR(254) NOT NULL,
    name          VARCHAR(120) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,            -- BCrypt output is 60 chars
    role          VARCHAR(20)  NOT NULL,
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version       BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_app_user_role   CHECK (role   IN ('ADMIN', 'ORGANIZER', 'TEAM_MEMBER', 'VIEWER')),
    CONSTRAINT ck_app_user_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

-- Case-insensitive uniqueness without the citext extension.
CREATE UNIQUE INDEX ux_app_user_email_lower ON app_user (lower(email));

CREATE TABLE team (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(120) NOT NULL,
    description TEXT,
    created_by  UUID         REFERENCES app_user (id),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version     BIGINT       NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX ux_team_name_lower ON team (lower(name));

CREATE TABLE team_membership (
    team_id          UUID        NOT NULL REFERENCES team (id) ON DELETE CASCADE,
    user_id          UUID        NOT NULL REFERENCES app_user (id),
    team_role        VARCHAR(20) NOT NULL DEFAULT 'MEMBER',
    -- Availability flag consulted by responder selection.
    available        BOOLEAN     NOT NULL DEFAULT TRUE,
    -- Position in the escalation chain (1 = first responder). NULL = not a responder.
    -- The lead is expected to hold the highest number so escalation ends at a human lead.
    escalation_order INTEGER,
    joined_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (team_id, user_id),
    CONSTRAINT ck_team_membership_role  CHECK (team_role IN ('LEAD', 'RESPONDER', 'MEMBER')),
    CONSTRAINT ck_team_membership_order CHECK (escalation_order IS NULL OR escalation_order >= 1)
);

-- Two members of one team cannot share an escalation position.
CREATE UNIQUE INDEX ux_team_membership_order
    ON team_membership (team_id, escalation_order) WHERE escalation_order IS NOT NULL;

CREATE INDEX ix_team_membership_user ON team_membership (user_id);
