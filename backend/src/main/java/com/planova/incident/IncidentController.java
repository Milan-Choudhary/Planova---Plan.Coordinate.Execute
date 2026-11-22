package com.planova.incident;

import com.planova.common.PageResponse;
import com.planova.identity.Actor;
import com.planova.incident.IncidentDtos.ActionRequest;
import com.planova.incident.IncidentDtos.AssignRequest;
import com.planova.incident.IncidentDtos.IncidentHistoryResponse;
import com.planova.incident.IncidentDtos.IncidentResponse;
import com.planova.incident.IncidentDtos.ReportIncidentRequest;
import com.planova.incident.IncidentDtos.ReportResult;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin HTTP layer: parse, call the service, shape the response. All rules live in
 * {@link IncidentService}; authentication is mandatory for /api/** (SecurityConfig).
 */
@RestController
@RequestMapping("/api/v1")
public class IncidentController {

    private final IncidentService service;

    public IncidentController(IncidentService service) {
        this.service = service;
    }

    /** 201 for a new incident; 200 with the ORIGINAL incident when the same Idempotency-Key is replayed. */
    @PostMapping("/plans/{planId}/incidents")
    public ResponseEntity<IncidentResponse> report(Authentication auth, @PathVariable UUID planId,
                                                   @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                   @Valid @RequestBody ReportIncidentRequest request) {
        ReportResult result = service.report(planId, request, key, Actor.of(auth));
        if (result.created()) {
            return ResponseEntity.created(URI.create("/api/v1/incidents/" + result.incident().id()))
                    .body(result.incident());
        }
        return ResponseEntity.ok(result.incident());
    }

    @GetMapping("/incidents")
    public PageResponse<IncidentResponse> list(Authentication auth,
                                               @RequestParam(required = false) UUID planId,
                                               @RequestParam(required = false) List<IncidentStatus> status,
                                               @RequestParam(defaultValue = "false") boolean assignedToMe,
                                               @RequestParam(defaultValue = "false") boolean unresolvedOnly,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        Set<IncidentStatus> statuses = status == null || status.isEmpty()
                ? Set.of() : EnumSet.copyOf(status);
        return service.list(Actor.of(auth), planId, statuses, assignedToMe, unresolvedOnly, page, size);
    }

    @GetMapping("/incidents/{id}")
    public IncidentResponse get(Authentication auth, @PathVariable UUID id) {
        return service.get(id, Actor.of(auth));
    }

    @GetMapping("/incidents/{id}/history")
    public IncidentHistoryResponse history(Authentication auth, @PathVariable UUID id) {
        return service.history(id, Actor.of(auth));
    }

    @PostMapping("/incidents/{id}/assign")
    public IncidentResponse assign(Authentication auth, @PathVariable UUID id,
                                   @Valid @RequestBody AssignRequest request) {
        return service.assign(id, request, Actor.of(auth));
    }

    @PostMapping("/incidents/{id}/acknowledge")
    public IncidentResponse acknowledge(Authentication auth, @PathVariable UUID id,
                                        @Valid @RequestBody(required = false) ActionRequest request) {
        return service.acknowledge(id, request, Actor.of(auth));
    }

    @PostMapping("/incidents/{id}/reject")
    public IncidentResponse reject(Authentication auth, @PathVariable UUID id,
                                   @Valid @RequestBody(required = false) ActionRequest request) {
        return service.reject(id, request, Actor.of(auth));
    }

    @PostMapping("/incidents/{id}/start")
    public IncidentResponse start(Authentication auth, @PathVariable UUID id,
                                  @Valid @RequestBody(required = false) ActionRequest request) {
        return service.start(id, request, Actor.of(auth));
    }

    @PostMapping("/incidents/{id}/resolve")
    public IncidentResponse resolve(Authentication auth, @PathVariable UUID id,
                                    @Valid @RequestBody(required = false) ActionRequest request) {
        return service.resolve(id, request, Actor.of(auth));
    }

    @PostMapping("/incidents/{id}/close")
    public IncidentResponse close(Authentication auth, @PathVariable UUID id,
                                  @Valid @RequestBody(required = false) ActionRequest request) {
        return service.close(id, request, Actor.of(auth));
    }

    @PostMapping("/incidents/{id}/reopen")
    public IncidentResponse reopen(Authentication auth, @PathVariable UUID id,
                                   @Valid @RequestBody(required = false) ActionRequest request) {
        return service.reopen(id, request, Actor.of(auth));
    }
}
