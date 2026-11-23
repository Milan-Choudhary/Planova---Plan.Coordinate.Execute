package com.planova.planning;

import com.planova.common.PageResponse;
import com.planova.identity.Actor;
import com.planova.planning.PlanningDtos.CreateTaskRequest;
import com.planova.planning.PlanningDtos.TaskResponse;
import com.planova.planning.PlanningDtos.UpdateTaskRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class TaskController {

    private final TaskService service;

    public TaskController(TaskService service) {
        this.service = service;
    }

    @PostMapping("/plans/{planId}/tasks")
    public ResponseEntity<TaskResponse> create(Authentication auth, @PathVariable UUID planId,
                                               @Valid @RequestBody CreateTaskRequest request) {
        TaskResponse created = service.create(Actor.of(auth), planId, request);
        return ResponseEntity.created(URI.create("/api/v1/tasks/" + created.id())).body(created);
    }

    @GetMapping("/plans/{planId}/tasks")
    public PageResponse<TaskResponse> forPlan(Authentication auth, @PathVariable UUID planId,
                                              @RequestParam(required = false) TaskStatus status,
                                              @RequestParam(required = false) UUID assigneeId,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "50") int size) {
        return service.listForPlan(Actor.of(auth), planId, status, assigneeId, page, size);
    }

    @GetMapping("/tasks/mine")
    public PageResponse<TaskResponse> mine(Authentication auth,
                                           @RequestParam(defaultValue = "true") boolean openOnly,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "50") int size) {
        return service.mine(Actor.of(auth), openOnly, page, size);
    }

    @GetMapping("/tasks/{id}")
    public TaskResponse get(Authentication auth, @PathVariable UUID id) {
        return service.get(Actor.of(auth), id);
    }

    @PatchMapping("/tasks/{id}")
    public TaskResponse update(Authentication auth, @PathVariable UUID id,
                               @Valid @RequestBody UpdateTaskRequest request) {
        return service.update(Actor.of(auth), id, request);
    }
}
