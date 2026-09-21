package com.fraudplatform.simulators;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Fictional customer-side systems used for local development, demos and the troubleshooting lab:
 * <ul>
 *   <li>{@code /crm/v1}          customer profile API (Aldermoor's CRM)</li>
 *   <li>{@code /device-intel/v1} device & IP risk vendor</li>
 *   <li>{@code /cases/v1}        case management system</li>
 *   <li>{@code /admin/faults}    fault injection per simulated system</li>
 * </ul>
 * Deterministic by design: the same request always gets the same answer (unless a fault is injected).
 */
@SpringBootApplication
public class SimulatorsApplication {

    public static void main(String[] args) {
        SpringApplication.run(SimulatorsApplication.class, args);
    }
}
