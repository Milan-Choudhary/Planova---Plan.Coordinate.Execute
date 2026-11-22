package com.planova.identity;

/**
 * Global (system-wide) role. Exactly one per user - see DECISIONS.md D-004.
 * Resource-level rights (who may edit THIS plan) come from per-plan membership, added in Phase 3.
 *
 * <ul>
 *   <li>ADMIN - manages users and roles, sees everything, handles escalations</li>
 *   <li>ORGANIZER - creates plans/events/tasks and assigns work</li>
 *   <li>TEAM_MEMBER - works on assigned tasks, can report and respond to incidents</li>
 *   <li>VIEWER - read-only</li>
 * </ul>
 */
public enum Role {
    ADMIN,
    ORGANIZER,
    TEAM_MEMBER,
    VIEWER
}
