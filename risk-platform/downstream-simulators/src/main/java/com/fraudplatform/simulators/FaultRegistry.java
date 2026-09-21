package com.fraudplatform.simulators;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Per-system fault injection: latency, error rate/status, and malformed (contract-violating) bodies.
 * {@code POST /admin/faults/{system}} with {@code {"latencyMs":300,"errorRate":0.5,"errorStatus":503}}.
 */
@Component
public class FaultRegistry extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(FaultRegistry.class);

    public record Fault(long latencyMs, double errorRate, int errorStatus, boolean malformed) {
    }

    private static final Map<String, String> PREFIX_TO_SYSTEM = Map.of(
            "/crm/", "crm", "/device-intel/", "device-intel", "/cases/", "cases");

    private final Map<String, Fault> faults = new ConcurrentHashMap<>();

    public void set(String system, Fault fault) {
        faults.put(system, fault);
        log.warn("FAULT system={} {}", system, fault);
    }

    public void clear() {
        faults.clear();
        log.warn("faults cleared");
    }

    public Map<String, Fault> active() {
        return Map.copyOf(faults);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String system = PREFIX_TO_SYSTEM.entrySet().stream().filter(e -> req.getRequestURI().startsWith(e.getKey()))
                .map(Map.Entry::getValue).findFirst().orElse(null);
        Fault f = system == null ? null : faults.get(system);
        if (f != null) {
            if (f.latencyMs() > 0) {
                try {
                    Thread.sleep(f.latencyMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (f.errorRate() > 0 && ThreadLocalRandom.current().nextDouble() < f.errorRate()) {
                res.setStatus(f.errorStatus() > 0 ? f.errorStatus() : 503);
                res.setContentType("application/json");
                res.getWriter().write("{\"error\":\"injected fault\"}");
                return;
            }
            if (f.malformed()) {
                res.setStatus(200);
                res.setContentType("application/json");
                res.getWriter().write("{\"unexpected\":true}");
                return;
            }
        }
        chain.doFilter(req, res);
    }

    @RestController
    @RequestMapping("/admin/faults")
    static class FaultController {

        private final FaultRegistry registry;

        FaultController(FaultRegistry registry) {
            this.registry = registry;
        }

        @GetMapping
        Map<String, Fault> list() {
            return registry.active();
        }

        @PostMapping("/{system}")
        ResponseEntity<Map<String, Fault>> set(@PathVariable String system, @RequestBody Fault fault) {
            if (!PREFIX_TO_SYSTEM.containsValue(system)) return ResponseEntity.badRequest().build();
            registry.set(system, fault);
            return ResponseEntity.ok(registry.active());
        }

        @DeleteMapping
        Map<String, Fault> clear() {
            registry.clear();
            return registry.active();
        }
    }
}
