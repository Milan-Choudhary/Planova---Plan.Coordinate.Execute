package com.planova.incident;

import java.util.List;
import java.util.Locale;

/**
 * Rule-based severity (the MVP's "CHECK_SEVERITY" step). Deterministic and explainable - no AI.
 *
 * <p>Rules can only RAISE the severity the reporter chose, never lower it, so a keyword match
 * cannot hide a problem the human thought was serious. The reason is stored in the audit trail.
 */
public final class SeverityPolicy {

    public record Result(Severity severity, String reason) {
    }

    private static final List<String> CRITICAL_WORDS =
            List.of("fire", "injury", "injured", "medical", "evacuat", "smoke", "collapse");
    private static final List<String> HIGH_WORDS =
            List.of("outage", "down", "crash", "security", "breach", "data loss", "power cut", "not working", "failed");

    private SeverityPolicy() {
    }

    /** @param requested what the reporter picked; null means "not specified" (defaults to MEDIUM). */
    public static Result evaluate(String title, String description, Severity requested) {
        Severity base = requested != null ? requested : Severity.MEDIUM;
        String text = ((title == null ? "" : title) + " " + (description == null ? "" : description))
                .toLowerCase(Locale.ROOT);

        for (String w : CRITICAL_WORDS) {
            if (text.contains(w) && base.ordinal() < Severity.CRITICAL.ordinal()) {
                return new Result(Severity.CRITICAL, "raised to CRITICAL: text contains '" + w + "'");
            }
        }
        for (String w : HIGH_WORDS) {
            if (text.contains(w) && base.ordinal() < Severity.HIGH.ordinal()) {
                return new Result(Severity.HIGH, "raised to HIGH: text contains '" + w + "'");
            }
        }
        return new Result(base, requested != null ? "kept as reported" : "defaulted to MEDIUM");
    }
}
