package com.planova.incident;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pure rule classes (no Spring, no database, no Docker).
 * Uses only basic JUnit assertions so it also runs under a minimal harness (see scripts/).
 */
class IncidentDomainTest {

    // ---------------------------------------------------------------- state machine

    @Test
    void allowedTransitionsMatchStateMachineDocument() {
        assertTrue(IncidentStatus.OPEN.canTransitionTo(IncidentStatus.ASSIGNED));
        assertTrue(IncidentStatus.ASSIGNED.canTransitionTo(IncidentStatus.ACKNOWLEDGED));
        assertTrue(IncidentStatus.ACKNOWLEDGED.canTransitionTo(IncidentStatus.IN_PROGRESS));
        assertTrue(IncidentStatus.ACKNOWLEDGED.canTransitionTo(IncidentStatus.RESOLVED));
        assertTrue(IncidentStatus.IN_PROGRESS.canTransitionTo(IncidentStatus.RESOLVED));
        assertTrue(IncidentStatus.RESOLVED.canTransitionTo(IncidentStatus.CLOSED));
        assertTrue(IncidentStatus.RESOLVED.canTransitionTo(IncidentStatus.OPEN));
        assertTrue(IncidentStatus.CLOSED.canTransitionTo(IncidentStatus.OPEN));
    }

    @Test
    void everyOtherTransitionIsRejected() {
        int allowed = 0;
        for (IncidentStatus from : IncidentStatus.values()) {
            for (IncidentStatus to : IncidentStatus.values()) {
                if (from.canTransitionTo(to)) {
                    allowed++;
                }
            }
        }
        assertEquals(8, allowed);   // exactly the 8 documented arrows, nothing implicit
        assertFalse(IncidentStatus.OPEN.canTransitionTo(IncidentStatus.RESOLVED));
        assertFalse(IncidentStatus.OPEN.canTransitionTo(IncidentStatus.CLOSED));
        assertFalse(IncidentStatus.ASSIGNED.canTransitionTo(IncidentStatus.RESOLVED));
        assertFalse(IncidentStatus.ASSIGNED.canTransitionTo(IncidentStatus.IN_PROGRESS));
        assertFalse(IncidentStatus.CLOSED.canTransitionTo(IncidentStatus.RESOLVED));
        assertFalse(IncidentStatus.RESOLVED.canTransitionTo(IncidentStatus.ACKNOWLEDGED));
        assertFalse(IncidentStatus.IN_PROGRESS.canTransitionTo(IncidentStatus.ASSIGNED));
        for (IncidentStatus s : IncidentStatus.values()) {
            assertFalse(s.canTransitionTo(s));   // no self-transitions
        }
    }

    @Test
    void helperPredicates() {
        assertTrue(IncidentStatus.OPEN.canBeAssigned());
        assertTrue(IncidentStatus.IN_PROGRESS.canBeAssigned());
        assertFalse(IncidentStatus.RESOLVED.canBeAssigned());
        assertFalse(IncidentStatus.CLOSED.canBeAssigned());
        assertTrue(IncidentStatus.ASSIGNED.isActiveWork());
        assertFalse(IncidentStatus.OPEN.isActiveWork());
        assertTrue(IncidentStatus.OPEN.isUnresolved());
        assertFalse(IncidentStatus.CLOSED.isUnresolved());
    }

    // ---------------------------------------------------------------- severity rules

    @Test
    void severityIsKeptWhenNothingMatches() {
        SeverityPolicy.Result r = SeverityPolicy.evaluate("Projector bulb", "Needs replacing", Severity.LOW);
        assertEquals(Severity.LOW, r.severity());
    }

    @Test
    void defaultsToMediumWhenNotSpecified() {
        assertEquals(Severity.MEDIUM, SeverityPolicy.evaluate("Chairs", "Need more chairs", null).severity());
    }

    @Test
    void keywordsRaiseSeverity() {
        assertEquals(Severity.HIGH,
                SeverityPolicy.evaluate("Auditorium Wi-Fi down", "Nobody can connect", Severity.LOW).severity());
        assertEquals(Severity.CRITICAL,
                SeverityPolicy.evaluate("Smoke near stage", "Alarm triggered", Severity.LOW).severity());
        assertEquals(Severity.CRITICAL,
                SeverityPolicy.evaluate("Volunteer injured", "Needs help", Severity.MEDIUM).severity());
    }

    @Test
    void keywordsNeverLowerSeverity() {
        assertEquals(Severity.CRITICAL,
                SeverityPolicy.evaluate("Wi-Fi down", "outage", Severity.CRITICAL).severity());
        assertEquals(Severity.HIGH,
                SeverityPolicy.evaluate("Wi-Fi down", "outage", Severity.HIGH).severity());
    }

    @Test
    void handlesNullText() {
        assertEquals(Severity.LOW, SeverityPolicy.evaluate(null, null, Severity.LOW).severity());
    }

    @Test
    void reasonExplainsTheDecision() {
        assertTrue(SeverityPolicy.evaluate("fire", "", Severity.LOW).reason().contains("fire"));
    }

    // ---------------------------------------------------------------- responder selection

    private static ResponderSelector.Candidate c(UUID id, int order, int load) {
        return new ResponderSelector.Candidate(id, order, load);
    }

    @Test
    void initialPickIsLeastLoadedThenLowestPosition() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID d = UUID.randomUUID();
        assertEquals(b, ResponderSelector.pickInitial(List.of(c(a, 1, 3), c(b, 2, 0), c(d, 3, 1))).orElseThrow().userId());
        // tie on load -> lowest escalation position wins
        assertEquals(a, ResponderSelector.pickInitial(List.of(c(d, 3, 1), c(a, 1, 1), c(b, 2, 1))).orElseThrow().userId());
    }

    @Test
    void initialPickIsEmptyWithoutCandidates() {
        assertTrue(ResponderSelector.pickInitial(List.of()).isEmpty());
    }

    @Test
    void escalationFollowsTheChainAndSkipsGaps() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID d = UUID.randomUUID();
        // position 2 (b) is unavailable, so it is simply absent from the candidate list
        List<ResponderSelector.Candidate> chain = List.of(c(a, 1, 0), c(d, 3, 0));
        assertEquals(d, ResponderSelector.pickNext(chain, 1, a).orElseThrow().userId());
        assertTrue(ResponderSelector.pickNext(chain, 3, d).isEmpty());   // end of chain
        // a current assignee outside the chain (manually assigned) starts from the top, never re-picks them
        assertEquals(a, ResponderSelector.pickNext(chain, 0, b).orElseThrow().userId());
    }

    @Test
    void escalationNeverPicksTheCurrentAssignee() {
        UUID a = UUID.randomUUID();
        assertTrue(ResponderSelector.pickNext(List.of(c(a, 5, 0)), 0, a).isEmpty());
    }

    // ---------------------------------------------------------------- workflow config

    private static Map<Severity, Integer> timeouts(int c, int h, int m, int l) {
        Map<Severity, Integer> t = new EnumMap<>(Severity.class);
        t.put(Severity.CRITICAL, c);
        t.put(Severity.HIGH, h);
        t.put(Severity.MEDIUM, m);
        t.put(Severity.LOW, l);
        return t;
    }

    @Test
    void validConfigPassesAndGivesTimeouts() {
        WorkflowConfig cfg = new WorkflowConfig(timeouts(5, 15, 30, 60), 3);
        assertTrue(cfg.validate().isEmpty());
        assertEquals(Duration.ofMinutes(5), cfg.ackTimeout(Severity.CRITICAL));
        assertEquals(Duration.ofMinutes(60), cfg.ackTimeout(Severity.LOW));
    }

    @Test
    void invalidConfigIsRejected() {
        assertFalse(new WorkflowConfig(null, 3).validate().isEmpty());
        assertFalse(new WorkflowConfig(timeouts(0, 15, 30, 60), 3).validate().isEmpty());
        assertFalse(new WorkflowConfig(timeouts(5, 15, 30, 5000), 3).validate().isEmpty());
        assertFalse(new WorkflowConfig(timeouts(5, 15, 30, 60), -1).validate().isEmpty());
        assertFalse(new WorkflowConfig(timeouts(5, 15, 30, 60), 11).validate().isEmpty());
        Map<Severity, Integer> missing = timeouts(5, 15, 30, 60);
        missing.remove(Severity.HIGH);
        assertEquals(1, new WorkflowConfig(missing, 3).validate().size());
    }
}
