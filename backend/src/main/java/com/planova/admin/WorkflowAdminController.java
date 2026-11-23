package com.planova.admin;

import com.planova.incident.IncidentRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only view of the active workflow definition (editing it is a documented non-goal for the MVP). */
@RestController
@RequestMapping("/api/v1/admin/workflow")
@PreAuthorize("hasRole('ADMIN')")
public class WorkflowAdminController {

    public record WorkflowView(String definitionId, String definitionJson) {
    }

    private final IncidentRepository repo;

    public WorkflowAdminController(IncidentRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public WorkflowView active() {
        IncidentRepository.Definition d = repo.activeDefinition();
        return new WorkflowView(d.id().toString(), d.json());
    }
}
