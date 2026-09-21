package com.fraudplatform.decision.api;

import com.fraudplatform.commons.error.ErrorCode;
import com.fraudplatform.commons.error.PlatformException;
import com.fraudplatform.decision.api.security.ApiClientPrincipal;
import com.fraudplatform.decision.messaging.DltOperations;
import com.fraudplatform.decision.messaging.OutboxRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Event operations: outbox backlog, replay from the outbox, DLT peek and redrive (role ADMIN). */
@RestController
@RequestMapping("/v1/admin/tenants/{tenant}/events")
public class EventAdminController {

    private final OutboxRepository outbox;
    private final Optional<DltOperations> dlt;

    public EventAdminController(OutboxRepository outbox, Optional<DltOperations> dlt) {
        this.outbox = outbox;
        this.dlt = dlt;
    }

    private static void authorize(ApiClientPrincipal c, String tenant) {
        if (!c.tenantId().equals(tenant)) throw new PlatformException(ErrorCode.FORBIDDEN, "credential is not valid for tenant " + tenant);
    }

    @GetMapping("/outbox")
    public OutboxRepository.Backlog backlog(@AuthenticationPrincipal ApiClientPrincipal c, @PathVariable String tenant) {
        authorize(c, tenant);
        return outbox.backlog();
    }

    public record ReplayRequest(String eventType, Instant from, Instant to) {
    }

    /**
     * Replay = mark already-published events in a time window as unpublished; the relay sends them again.
     * Safe because every consumer de-duplicates on eventId. For consumers that need to rebuild state from
     * scratch, reset their group offsets instead (documented in MESSAGING_AND_EVENTS.md).
     */
    @PostMapping("/replay")
    public Map<String, Object> replay(@AuthenticationPrincipal ApiClientPrincipal c, @PathVariable String tenant,
                                      @RequestBody ReplayRequest req) {
        authorize(c, tenant);
        if (req.from() == null || req.to() == null || !req.from().isBefore(req.to())) {
            throw new PlatformException(ErrorCode.VALIDATION_FAILED, "from/to required and from < to");
        }
        return Map.of("requeued", outbox.requeue(tenant, req.eventType(), req.from(), req.to()));
    }

    @GetMapping("/dlt")
    public List<DltOperations.DltRecord> peek(@AuthenticationPrincipal ApiClientPrincipal c, @PathVariable String tenant,
                                              @RequestParam String topic, @RequestParam(defaultValue = "50") int max) {
        authorize(c, tenant);
        return dlt.orElseThrow(() -> new PlatformException(ErrorCode.DEPENDENCY_UNAVAILABLE, "messaging disabled"))
                .peek(requireDlt(topic), Math.min(max, 500));
    }

    @PostMapping("/dlt/redrive")
    public Map<String, Object> redrive(@AuthenticationPrincipal ApiClientPrincipal c, @PathVariable String tenant,
                                       @RequestParam String topic, @RequestParam(defaultValue = "100") int max) {
        authorize(c, tenant);
        return dlt.orElseThrow(() -> new PlatformException(ErrorCode.DEPENDENCY_UNAVAILABLE, "messaging disabled"))
                .redrive(requireDlt(topic), Math.min(max, 1000));
    }

    private static String requireDlt(String topic) {
        if (!topic.endsWith(".dlt")) throw new PlatformException(ErrorCode.VALIDATION_FAILED, "not a dead-letter topic");
        return topic;
    }
}
