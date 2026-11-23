package com.planova.planning;

import com.planova.identity.Actor;
import com.planova.planning.PlanningDtos.AvailabilityRequest;
import com.planova.planning.PlanningDtos.CreateTeamRequest;
import com.planova.planning.PlanningDtos.TeamDetailResponse;
import com.planova.planning.PlanningDtos.TeamMemberRequest;
import com.planova.planning.PlanningDtos.TeamResponse;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/teams")
public class TeamController {

    private final TeamService service;

    public TeamController(TeamService service) {
        this.service = service;
    }

    @GetMapping
    public List<TeamResponse> list(Authentication auth) {
        return service.list(Actor.of(auth));
    }

    @GetMapping("/{id}")
    public TeamDetailResponse get(Authentication auth, @PathVariable UUID id) {
        return service.get(id, Actor.of(auth));
    }

    @PostMapping
    public ResponseEntity<TeamResponse> create(Authentication auth, @Valid @RequestBody CreateTeamRequest request) {
        TeamResponse created = service.create(Actor.of(auth), request);
        return ResponseEntity.created(URI.create("/api/v1/teams/" + created.id())).body(created);
    }

    @PostMapping("/{id}/members")
    public TeamDetailResponse upsertMember(Authentication auth, @PathVariable UUID id,
                                           @Valid @RequestBody TeamMemberRequest request) {
        return service.upsertMember(Actor.of(auth), id, request);
    }

    @DeleteMapping("/{id}/members/{userId}")
    public TeamDetailResponse removeMember(Authentication auth, @PathVariable UUID id, @PathVariable UUID userId) {
        return service.removeMember(Actor.of(auth), id, userId);
    }

    @PostMapping("/{id}/members/by-email")
    public TeamDetailResponse addByEmail(Authentication auth, @PathVariable UUID id,
                                         @Valid @RequestBody PlanningDtos.TeamMemberEmailRequest request) {
        return service.addByEmail(Actor.of(auth), id, request);
    }

    @PatchMapping("/{id}/members/{userId}/availability")
    public TeamDetailResponse availability(Authentication auth, @PathVariable UUID id, @PathVariable UUID userId,
                                           @Valid @RequestBody AvailabilityRequest request) {
        return service.setAvailability(Actor.of(auth), id, userId, request);
    }
}
