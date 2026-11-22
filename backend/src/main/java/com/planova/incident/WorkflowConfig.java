package com.planova.incident;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Typed contents of workflow_definition.definition (JSON). It carries only numbers - there is no
 * way to express code or new steps - so "never execute arbitrary code from workflow definitions"
 * holds by construction. {@link #validate()} must pass before a definition may be activated.
 *
 * @param ackTimeoutMinutes how long a responder has to acknowledge, per severity
 * @param maxEscalationLevel max automatic escalations after the first assignment
 */
public record WorkflowConfig(Map<Severity, Integer> ackTimeoutMinutes, int maxEscalationLevel) {

    public List<String> validate() {
        List<String> errors = new ArrayList<>();
        if (ackTimeoutMinutes == null) {
            errors.add("ackTimeoutMinutes is required");
        } else {
            for (Severity s : Severity.values()) {
                Integer m = ackTimeoutMinutes.get(s);
                if (m == null) {
                    errors.add("ackTimeoutMinutes." + s + " is missing");
                } else if (m < 1 || m > 1440) {
                    errors.add("ackTimeoutMinutes." + s + " must be between 1 and 1440");
                }
            }
        }
        if (maxEscalationLevel < 0 || maxEscalationLevel > 10) {
            errors.add("maxEscalationLevel must be between 0 and 10");
        }
        return errors;
    }

    public Duration ackTimeout(Severity severity) {
        return Duration.ofMinutes(ackTimeoutMinutes.get(severity));
    }
}
