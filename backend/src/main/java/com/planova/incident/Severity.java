package com.planova.incident;

public enum Severity {
    LOW, MEDIUM, HIGH, CRITICAL;

    public static Severity max(Severity a, Severity b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
