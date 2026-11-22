package com.planova.incident;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.planova.planning.TaskStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Pure unit tests for the "who may do what" policy and the task state machine. */
class PolicyAndTaskStatusTest {

    private static List<String> actions(IncidentStatus s, boolean assignee, boolean manage, boolean reporter) {
        return IncidentPolicy.allowedActions(s, assignee, manage, reporter);
    }

    @Test
    void assigneeOfAssignedIncidentMayAcknowledgeOrReject() {
        List<String> a = actions(IncidentStatus.ASSIGNED, true, false, false);
        assertTrue(a.contains("ACKNOWLEDGE"));
        assertTrue(a.contains("REJECT"));
        assertFalse(a.contains("RESOLVE"));
        assertFalse(a.contains("ASSIGN"));
    }

    @Test
    void strangersAndViewersGetNoActions() {
        for (IncidentStatus s : IncidentStatus.values()) {
            assertTrue(actions(s, false, false, false).isEmpty());
        }
    }

    @Test
    void managerCannotAcknowledgeOnBehalfOfTheAssignee() {
        assertFalse(actions(IncidentStatus.ASSIGNED, false, true, false).contains("ACKNOWLEDGE"));
        assertTrue(actions(IncidentStatus.ASSIGNED, false, true, false).contains("ASSIGN"));
    }

    @Test
    void managerMayResolveAnActiveIncident() {
        assertTrue(actions(IncidentStatus.IN_PROGRESS, false, true, false).contains("RESOLVE"));
        assertTrue(actions(IncidentStatus.ACKNOWLEDGED, false, true, false).contains("RESOLVE"));
        assertFalse(actions(IncidentStatus.ASSIGNED, false, true, false).contains("RESOLVE"));
    }

    @Test
    void startOnlyAfterAcknowledgementAndOnlyByAssignee() {
        assertTrue(actions(IncidentStatus.ACKNOWLEDGED, true, false, false).contains("START"));
        assertFalse(actions(IncidentStatus.ASSIGNED, true, false, false).contains("START"));
        assertFalse(actions(IncidentStatus.ACKNOWLEDGED, false, true, false).contains("START"));
    }

    @Test
    void reporterMayCloseAndReopenResolvedIncident() {
        List<String> a = actions(IncidentStatus.RESOLVED, false, false, true);
        assertTrue(a.contains("CLOSE"));
        assertTrue(a.contains("REOPEN"));
        assertFalse(a.contains("ASSIGN"));
    }

    @Test
    void closedIncidentCanOnlyBeReopened() {
        List<String> a = actions(IncidentStatus.CLOSED, false, true, false);
        assertEquals(List.of("REOPEN"), a);
    }

    @Test
    void openIncidentCanBeAssignedByManagerOnly() {
        assertEquals(List.of("ASSIGN"), actions(IncidentStatus.OPEN, false, true, false));
        assertTrue(actions(IncidentStatus.OPEN, false, false, true).isEmpty());
    }

    @Test
    void taskTransitions() {
        assertTrue(TaskStatus.TODO.canTransitionTo(TaskStatus.IN_PROGRESS));
        assertTrue(TaskStatus.IN_PROGRESS.canTransitionTo(TaskStatus.DONE));
        assertTrue(TaskStatus.DONE.canTransitionTo(TaskStatus.IN_PROGRESS));
        assertTrue(TaskStatus.CANCELLED.canTransitionTo(TaskStatus.TODO));
        assertFalse(TaskStatus.TODO.canTransitionTo(TaskStatus.DONE));      // must be started first
        assertFalse(TaskStatus.DONE.canTransitionTo(TaskStatus.CANCELLED));
        assertFalse(TaskStatus.CANCELLED.canTransitionTo(TaskStatus.DONE));
        for (TaskStatus s : TaskStatus.values()) {
            assertFalse(s.canTransitionTo(s));
        }
    }

    @Test
    void onlyTodoAndInProgressAreOpen() {
        assertTrue(TaskStatus.TODO.isOpen());
        assertTrue(TaskStatus.IN_PROGRESS.isOpen());
        assertFalse(TaskStatus.DONE.isOpen());
        assertFalse(TaskStatus.CANCELLED.isOpen());
    }
}
