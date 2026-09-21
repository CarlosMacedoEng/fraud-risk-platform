package com.fraudplatform.decision.api;

import com.fraudplatform.commons.correlation.Correlation;
import com.fraudplatform.commons.error.ErrorCode;
import com.fraudplatform.commons.error.PlatformException;
import com.fraudplatform.decision.api.dto.ScoreRequest;
import com.fraudplatform.decision.api.dto.ScoreResponse;
import com.fraudplatform.decision.api.security.ApiClientPrincipal;
import com.fraudplatform.decision.application.DecisionService;
import com.fraudplatform.decision.config.PlatformProperties;
import com.fraudplatform.decision.domain.Decision;
import com.fraudplatform.decision.domain.RiskDecision;
import com.fraudplatform.decision.domain.Transaction;
import com.fraudplatform.decision.persistence.DecisionRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/decisions")
@Validated
public class DecisionController {

    static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    private static final int MAX_REASONS = 5;

    private final DecisionService service;
    private final DecisionRepository decisions;
    private final ObjectMapper json;
    private final PlatformProperties props;

    public DecisionController(DecisionService service, DecisionRepository decisions, ObjectMapper json, PlatformProperties props) {
        this.service = service;
        this.decisions = decisions;
        this.json = json;
        this.props = props;
    }

    @PostMapping
    public ResponseEntity<ScoreResponse> score(
            @AuthenticationPrincipal ApiClientPrincipal client,
            @RequestHeader(name = IDEMPOTENCY_HEADER, required = false)
            @Size(max = 128) @Pattern(regexp = "[A-Za-z0-9._:-]+") String idempotencyKey,
            @Valid @RequestBody ScoreRequest request) {
        long start = System.nanoTime();
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new PlatformException(ErrorCode.MISSING_IDEMPOTENCY_KEY, null);
        }
        MDC.put(Correlation.MDC_TRANSACTION_ID, request.transactionId());
        Transaction tx = toDomain(client.tenantId(), request);
        var result = service.score(new DecisionService.Command(tx, client.clientId(), idempotencyKey, hash(request),
                MDC.get(Correlation.MDC_CORRELATION_ID), start));
        double totalMs = (System.nanoTime() - start) / 1e6;
        return ResponseEntity.ok()
                .header("Idempotent-Replayed", Boolean.toString(result.replayed()))
                .header("Server-Timing", "total;dur=%.2f, decision;dur=%.2f".formatted(totalMs, result.decision().processingMs()))
                .body(ScoreResponse.of(result.decision(), result.replayed(), MAX_REASONS));
    }

    @GetMapping("/{decisionId}")
    public ScoreResponse get(@AuthenticationPrincipal ApiClientPrincipal client, @PathVariable UUID decisionId) {
        RiskDecision d = decisions.findById(client.tenantId(), decisionId)
                .orElseThrow(() -> new PlatformException(ErrorCode.NOT_FOUND, "decision not found"));
        return ScoreResponse.of(d, false, Integer.MAX_VALUE);
    }

    public record DecisionPage(List<ScoreResponse> items, String nextCursor) {
    }

    /** Keyset-paginated listing; {@code cursor} is opaque to clients. */
    @GetMapping
    public DecisionPage list(@AuthenticationPrincipal ApiClientPrincipal client,
                             @RequestParam(required = false) Decision decision,
                             @RequestParam(required = false) String cursor,
                             @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        Instant beforeTs = null;
        UUID beforeId = null;
        if (cursor != null) {
            try {
                String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|");
                beforeTs = Instant.parse(parts[0]);
                beforeId = UUID.fromString(parts[1]);
            } catch (RuntimeException e) {
                throw new PlatformException(ErrorCode.VALIDATION_FAILED, "invalid cursor");
            }
        }
        List<RiskDecision> rows = decisions.list(client.tenantId(), decision, beforeTs, beforeId, limit);
        String next = rows.size() < limit ? null : Base64.getUrlEncoder().withoutPadding().encodeToString(
                (rows.getLast().createdAt() + "|" + rows.getLast().decisionId()).getBytes(StandardCharsets.UTF_8));
        return new DecisionPage(rows.stream().map(d -> ScoreResponse.of(d, false, MAX_REASONS)).toList(), next);
    }

    private Transaction toDomain(String tenantId, ScoreRequest r) {
        return new Transaction(tenantId, r.transactionId(), r.customerId(), r.accountId(), r.eventTime(), r.transactionType(),
                r.channel(), r.amount(), r.currency(), r.cardToken(), r.merchantId(), r.mcc(), r.merchantCountry(),
                r.beneficiaryId(), r.beneficiaryCountry(), r.deviceId(), r.ipAddress(), r.ipCountry());
    }

    /** SHA-256 of the canonical request JSON: detects Idempotency-Key reuse with a different body. */
    private String hash(ScoreRequest request) {
        try {
            byte[] canonical = json.writeValueAsBytes(request);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (Exception e) {
            throw new PlatformException(ErrorCode.INTERNAL_ERROR, "cannot hash request", Map.of(), e);
        }
    }
}
