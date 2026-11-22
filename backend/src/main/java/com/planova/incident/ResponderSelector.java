package com.planova.incident;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Chooses who gets an incident. Pure functions over already-filtered candidates: the SQL has
 * already removed unavailable members and disabled users (see IncidentSql.TEAM_CANDIDATES).
 *
 * <ul>
 *   <li><b>Initial pick</b>: least-loaded responder (fewest open incidents); ties go to the
 *       lowest escalation position. Spreads work instead of burying the first responder.</li>
 *   <li><b>Escalation pick</b>: strictly follow the configured chain - the next position after the
 *       current assignee, skipping anyone unavailable. Predictable and explainable.</li>
 * </ul>
 */
public final class ResponderSelector {

    public record Candidate(UUID userId, int order, int openLoad) {
    }

    private ResponderSelector() {
    }

    public static Optional<Candidate> pickInitial(List<Candidate> candidates) {
        return candidates.stream()
                .min(Comparator.comparingInt(Candidate::openLoad).thenComparingInt(Candidate::order));
    }

    /**
     * @param afterOrder chain position of the current assignee (0 if they are not in the chain)
     * @param excluded   never pick this user (the current assignee)
     */
    public static Optional<Candidate> pickNext(List<Candidate> candidates, int afterOrder, UUID excluded) {
        return candidates.stream()
                .filter(c -> c.order() > afterOrder && !c.userId().equals(excluded))
                .min(Comparator.comparingInt(Candidate::order));
    }
}
