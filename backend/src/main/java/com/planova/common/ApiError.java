package com.planova.common;

import java.time.Instant;
import java.util.List;

/**
 * The ONE error shape every endpoint returns (documented in API_CONTRACTS.md).
 * Frontend code can therefore handle every failure with a single parser.
 *
 * @param code          stable machine-readable identifier, e.g. {@code VALIDATION_FAILED};
 *                      clients switch on this, never on {@code message}
 * @param message       human-readable text, safe to show to users (never contains SQL/stack traces)
 * @param correlationId same value as the {@code X-Correlation-Id} response header and the log lines
 * @param fieldErrors   per-field problems for validation failures; empty otherwise
 */
public record ApiError(
        Instant timestamp,
        int status,
        String code,
        String message,
        String path,
        String correlationId,
        List<FieldViolation> fieldErrors) {

    public record FieldViolation(String field, String message) {}
}
