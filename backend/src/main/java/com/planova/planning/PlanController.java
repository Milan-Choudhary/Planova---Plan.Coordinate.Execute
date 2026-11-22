package com.planova.planning;

import com.planova.identity.Actor;
import com.planova.common.PageResponse;
import com.planova.planning.PlanningDtos.CreatePlanRequest;
import com.planova.planning.PlanningDtos.EventRequest;
import com.planova.planning.PlanningDtos.EventResponse;
import com.planova.planning.PlanningDtos.PlanMemberRequest;
import com.planova.planning.PlanningDtos.PlanMemberResponse;
import com.planova.planning.PlanningDtos.PlanResponse;
import com.planova.planning.PlanningDtos.PlanStatusRequest;
import com.planova.planning.PlanningDtos.UpdatePlanRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/plans")
public class PlanController {

    private final PlanService service;

    public PlanController(PlanService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<PlanResponse> create(Authentication auth, @Valid @RequestBody CreatePlanRequest request) {
        PlanResponse created = service.create(Actor.of(auth), request);
        return ResponseEntity.created(URI.create("/api/v1/plans/" + created.id())).body(created);
    }

    @GetMapping
    public PageResponse<PlanResponse> list(Authentication auth, @RequestParam(required = false) String status,
                                           @RequestParam(required = false) String q,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        return service.list(Actor.of(auth), status, q, page, size);
    }

    @GetMapping("/{id}")
    public PlanResponse get(Authentication auth, @PathVariable UUID id) {
        return service.get(id, Actor.of(auth));
    }

    @PatchMapping("/{id}")
    public PlanResponse update(Authentication auth, @PathVariable UUID id,
                               @Valid @RequestBody UpdatePlanRequest request) {
        return service.update(Actor.of(auth), id, request);
    }

    @PostMapping("/{id}/status")
    public PlanResponse status(Authentication auth, @PathVariable UUID id,
                               @Valid @RequestBody PlanStatusRequest request) {
        return service.changeStatus(Actor.of(auth), id, request);
    }

    // ---- members
    @GetMapping("/{id}/members")
    public List<PlanMemberResponse> members(Authentication auth, @PathVariable UUID id) {
        return service.members(Actor.of(auth), id);
    }

    @PutMapping("/{id}/members")
    public List<PlanMemberResponse> setMember(Authentication auth, @PathVariable UUID id,
                                              @Valid @RequestBody PlanMemberRequest request) {
        return service.setMember(Actor.of(auth), id, request);
    }

    @DeleteMapping("/{id}/members/{userId}")
    public List<PlanMemberResponse> removeMember(Authentication auth, @PathVariable UUID id,
                                                 @PathVariable UUID userId) {
        return service.removeMember(Actor.of(auth), id, userId);
    }

    // ---- events
    @GetMapping("/{id}/events")
    public List<EventResponse> events(Authentication auth, @PathVariable UUID id) {
        return service.events(Actor.of(auth), id);
    }

    @PostMapping("/{id}/events")
    public ResponseEntity<EventResponse> createEvent(Authentication auth, @PathVariable UUID id,
                                                     @Valid @RequestBody EventRequest request) {
        EventResponse created = service.createEvent(Actor.of(auth), id, request);
        return ResponseEntity.created(URI.create("/api/v1/plans/" + id + "/events/" + created.id())).body(created);
    }

    @PutMapping("/{id}/events/{eventId}")
    public EventResponse updateEvent(Authentication auth, @PathVariable UUID id, @PathVariable UUID eventId,
                                     @Valid @RequestBody EventRequest request) {
        return service.updateEvent(Actor.of(auth), id, eventId, request);
    }

    @DeleteMapping("/{id}/events/{eventId}")
    public ResponseEntity<Void> deleteEvent(Authentication auth, @PathVariable UUID id, @PathVariable UUID eventId) {
        service.deleteEvent(Actor.of(auth), id, eventId);
        return ResponseEntity.noContent().build();
    }
}
