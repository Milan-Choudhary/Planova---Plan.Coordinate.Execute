package com.planova.audit;

import com.planova.access.AccessService;
import com.planova.common.PageResponse;
import com.planova.identity.Actor;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class AuditController {

    private final AuditQueryService audit;
    private final AccessService access;

    public AuditController(AuditQueryService audit, AccessService access) {
        this.audit = audit;
        this.access = access;
    }

    /** Audit trail of one plan: anyone who may read the plan may read its history (404 otherwise). */
    @GetMapping("/plans/{planId}/audit")
    public PageResponse<AuditQueryService.Entry> forPlan(Authentication auth, @PathVariable UUID planId,
                                                         @RequestParam(required = false) String action,
                                                         @RequestParam(defaultValue = "0") int page,
                                                         @RequestParam(defaultValue = "20") int size) {
        access.requireRead(planId, Actor.of(auth));
        return audit.search(planId, action, page, size);
    }

    /** Everything, including user-management and system events. Administrators only. */
    @GetMapping("/admin/audit")
    @PreAuthorize("hasRole('ADMIN')")
    public PageResponse<AuditQueryService.Entry> all(@RequestParam(required = false) String action,
                                                     @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "20") int size) {
        return audit.search(null, action, page, size);
    }
}
