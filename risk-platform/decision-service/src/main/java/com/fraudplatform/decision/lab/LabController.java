package com.fraudplatform.decision.lab;

import com.fraudplatform.commons.error.ErrorCode;
import com.fraudplatform.commons.error.PlatformException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Troubleshooting-lab endpoints (role ADMIN, and only when {@code platform.faults.enabled=true}, i.e. the
 * {@code lab} profile). They create realistic failure modes so the playbook can be practised with real
 * evidence: CPU burn, a memory leak, lock contention that pins virtual threads, slow/failing model and
 * feature store. Never enabled in production.
 */
@RestController
@RequestMapping("/lab")
public class LabController {

    private static final Logger log = LoggerFactory.getLogger(LabController.class);

    private final FaultInjector faults;
    /** Simulated leak: a "cache" that is never evicted (the classic unbounded-map leak). */
    static final List<byte[]> LEAK = new CopyOnWriteArrayList<>();

    public LabController(FaultInjector faults) {
        this.faults = faults;
    }

    private void requireEnabled() {
        if (!faults.enabled()) throw new PlatformException(ErrorCode.FORBIDDEN, "fault injection disabled (lab profile only)");
    }

    @GetMapping("/faults")
    public Map<String, Object> list() {
        requireEnabled();
        return Map.of("faults", faults.active(), "leakedMb", LEAK.size());
    }

    @PostMapping("/faults/{point}")
    public Map<String, Object> set(@PathVariable FaultInjector.Point point, @RequestBody FaultInjector.Fault fault) {
        requireEnabled();
        faults.set(point, fault);
        return list();
    }

    @DeleteMapping("/faults")
    public Map<String, Object> clear() {
        requireEnabled();
        faults.clear();
        LEAK.clear();
        log.warn("lab: faults cleared and leak released");
        return list();
    }

    /** Retain {@code mb} megabytes forever (until DELETE /lab/faults). */
    @PostMapping("/leak/{mb}")
    public Map<String, Object> leak(@PathVariable int mb) {
        requireEnabled();
        for (int i = 0; i < mb; i++) LEAK.add(new byte[1024 * 1024]);
        log.warn("lab: leaked {} MB (total {} MB)", mb, LEAK.size());
        return list();
    }
}
