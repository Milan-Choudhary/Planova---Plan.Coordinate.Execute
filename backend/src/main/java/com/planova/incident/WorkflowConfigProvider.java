package com.planova.incident;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Loads and VALIDATES the ACTIVE workflow definition from the database. An invalid definition makes
 * the incident pipeline fail loudly (recorded, bounded retries) rather than run with nonsense values.
 */
@Component
public class WorkflowConfigProvider {

    public record ActiveWorkflow(UUID definitionId, WorkflowConfig config) {
    }

    private final IncidentRepository repo;
    private final ObjectMapper mapper;

    public WorkflowConfigProvider(IncidentRepository repo, ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    public ActiveWorkflow active() {
        IncidentRepository.Definition def = repo.activeDefinition();
        try {
            WorkflowConfig config = mapper.readValue(def.json(), WorkflowConfig.class);
            List<String> errors = config.validate();
            if (!errors.isEmpty()) {
                throw new IllegalStateException("Invalid workflow definition " + def.id() + ": " + errors);
            }
            return new ActiveWorkflow(def.id(), config);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unreadable workflow definition " + def.id(), e);
        }
    }
}
