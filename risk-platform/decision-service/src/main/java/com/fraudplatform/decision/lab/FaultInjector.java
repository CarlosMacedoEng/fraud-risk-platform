package com.fraudplatform.decision.lab;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Deterministic fault injection for the troubleshooting lab. Disabled unless the {@code lab}
 * profile enables it; every change is logged at WARN so it is visible in the incident timeline.
 */
public class FaultInjector {

    private static final Logger log = LoggerFactory.getLogger(FaultInjector.class);

    public enum Point { MODEL_INFERENCE, FEATURE_STORE, DECISION_PERSISTENCE, CPU_BURN, LOCK_CONTENTION }

    public record Fault(long latencyMs, double errorRate) {
    }

    private final boolean enabled;
    /** Global lock for the contention fault: sleeping while holding a monitor pins virtual threads on Java 21. */
    private static final Object CONTENDED = new Object();
    private final Map<Point, Fault> faults = new EnumMap<>(Point.class);

    public FaultInjector(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    public synchronized void set(Point point, Fault fault) {
        if (!enabled) throw new IllegalStateException("fault injection disabled");
        faults.put(point, fault);
        log.warn("FAULT INJECTED point={} latencyMs={} errorRate={}", point, fault.latencyMs(), fault.errorRate());
    }

    public synchronized void clear() {
        faults.clear();
        log.warn("all injected faults cleared");
    }

    public synchronized Map<Point, Fault> active() {
        return Map.copyOf(faults);
    }

    public void apply(Point point) {
        if (!enabled) return;
        Fault f;
        synchronized (this) {
            f = faults.get(point);
        }
        if (f == null) return;
        if (f.latencyMs() > 0 && point == Point.LOCK_CONTENTION) {
            synchronized (CONTENDED) {
                try {
                    Thread.sleep(f.latencyMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        } else if (f.latencyMs() > 0) {
            if (point == Point.CPU_BURN) {
                long end = System.nanoTime() + f.latencyMs() * 1_000_000;
                double x = 0;
                while (System.nanoTime() < end) x += Math.sqrt(x + 1);
                if (x < 0) log.trace("unreachable {}", x);
            } else {
                try {
                    Thread.sleep(f.latencyMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        if (f.errorRate() > 0 && ThreadLocalRandom.current().nextDouble() < f.errorRate()) {
            throw new IllegalStateException("injected fault at " + point);
        }
    }
}
