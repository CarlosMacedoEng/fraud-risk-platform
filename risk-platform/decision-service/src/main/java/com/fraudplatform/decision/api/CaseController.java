package com.fraudplatform.decision.api;

import com.fraudplatform.decision.api.security.ApiClientPrincipal;
import com.fraudplatform.decision.application.CaseService;
import com.fraudplatform.decision.persistence.CaseRepository.FraudCase;
import com.fraudplatform.decision.persistence.IntegrationFailureRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Analyst case queue and outcomes (roles ANALYST / ADMIN). */
@RestController
@RequestMapping("/v1/cases")
public class CaseController {

    private final CaseService cases;
    private final IntegrationFailureRepository failures;

    public CaseController(CaseService cases, IntegrationFailureRepository failures) {
        this.cases = cases;
        this.failures = failures;
    }

    @GetMapping
    public List<FraudCase> queue(@AuthenticationPrincipal ApiClientPrincipal c, @RequestParam(defaultValue = "OPEN") String status,
                                 @RequestParam(defaultValue = "50") int limit) {
        return cases.queue(c.tenantId(), status, limit);
    }

    @GetMapping("/{caseId}")
    public FraudCase get(@AuthenticationPrincipal ApiClientPrincipal c, @PathVariable UUID caseId) {
        return cases.get(c.tenantId(), caseId);
    }

    public record Resolution(String outcome, String fraudType, String note) {
    }

    @PostMapping("/{caseId}/resolve")
    public FraudCase resolve(@AuthenticationPrincipal ApiClientPrincipal c, @PathVariable UUID caseId,
                             @RequestHeader("If-Match") int rowVersion, @RequestBody Resolution r) {
        return cases.resolve(c.tenantId(), caseId, r.outcome(), r.fraudType(), r.note(), c.clientId(), rowVersion);
    }

    /** Support view: asynchronous integration failures still unresolved for this tenant. */
    @GetMapping("/integration-failures")
    public List<IntegrationFailureRepository.Failure> integrationFailures(@AuthenticationPrincipal ApiClientPrincipal c) {
        return failures.open(c.tenantId(), 100);
    }
}
