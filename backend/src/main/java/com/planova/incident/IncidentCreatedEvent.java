package com.planova.incident;

import java.util.UUID;

/** Published inside the "report incident" transaction; handled only AFTER that transaction commits. */
public record IncidentCreatedEvent(UUID incidentId) {
}
