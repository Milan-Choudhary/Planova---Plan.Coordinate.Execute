package com.planova.incident;

import java.util.ArrayList;
import java.util.List;

/**
 * "Which buttons may this person press right now?" as a pure function.
 *
 * <p>The API returns this list as {@code allowedActions} on every incident so the UI never has to
 * re-implement (and drift from) the rules. It is advisory for display only: every action endpoint
 * re-checks the same rules on the server.
 */
public final class IncidentPolicy {

    public static final String ACKNOWLEDGE = "ACKNOWLEDGE";
    public static final String REJECT = "REJECT";
    public static final String START = "START";
    public static final String RESOLVE = "RESOLVE";
    public static final String ASSIGN = "ASSIGN";
    public static final String CLOSE = "CLOSE";
    public static final String REOPEN = "REOPEN";

    private IncidentPolicy() {
    }

    /**
     * @param isAssignee the caller is the CURRENT assignee
     * @param canManage  caller is an administrator or an editor of the incident's plan (and not a VIEWER)
     * @param isReporter the caller reported this incident
     */
    public static List<String> allowedActions(IncidentStatus status, boolean isAssignee, boolean canManage,
                                              boolean isReporter) {
        List<String> actions = new ArrayList<>();
        if (isAssignee && status == IncidentStatus.ASSIGNED) {
            actions.add(ACKNOWLEDGE);
            actions.add(REJECT);
        }
        if (isAssignee && status == IncidentStatus.ACKNOWLEDGED) {
            actions.add(START);
        }
        if ((status == IncidentStatus.ACKNOWLEDGED || status == IncidentStatus.IN_PROGRESS)
                && (isAssignee || canManage)) {
            actions.add(RESOLVE);
        }
        if (canManage && status.canBeAssigned()) {
            actions.add(ASSIGN);
        }
        if (status == IncidentStatus.RESOLVED && (canManage || isReporter)) {
            actions.add(CLOSE);
        }
        if ((status == IncidentStatus.RESOLVED || status == IncidentStatus.CLOSED) && (canManage || isReporter)) {
            actions.add(REOPEN);
        }
        return actions;
    }
}
