-- V5: default incident workflow + resolution note.
-- The workflow is a FIXED, typed pipeline (see STATE_MACHINES.md). The definition row only holds
-- tunable numbers; it cannot contain code or new steps.
-- Defined in a migration (not Java) so every environment starts with identical, versioned data.

ALTER TABLE incident ADD COLUMN resolution_note TEXT;

INSERT INTO workflow_definition (name, version, status, definition) VALUES (
    'incident-default', 1, 'ACTIVE',
    '{"ackTimeoutMinutes": {"CRITICAL": 5, "HIGH": 15, "MEDIUM": 30, "LOW": 60}, "maxEscalationLevel": 3}'::jsonb
);
