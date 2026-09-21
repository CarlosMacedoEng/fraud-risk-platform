package com.fraudplatform.simulators;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Simulated device & IP intelligence vendor. An IP is "compromised" if it appears in a threat feed file
 * exported by the ml-workbench (a partial sample of fraudster IPs — real feeds never have full coverage).
 */
@RestController
@RequestMapping("/device-intel/v1")
class DeviceIntelController {

    private static final Logger log = LoggerFactory.getLogger(DeviceIntelController.class);

    record AssessmentRequest(String tenantId, String customerId, String deviceId, String ipAddress, String ipCountry) {
    }

    record Assessment(String assessmentId, int riskScore, boolean compromisedIp, boolean emulator, boolean proxy,
                      List<String> signals) {
    }

    private final Set<String> threatFeed = new HashSet<>();

    DeviceIntelController(@Value("${simulators.threat-feed:../../data/samples/threat_feed_ips.txt}") Path feed) {
        try {
            if (Files.exists(feed)) {
                Files.readAllLines(feed).stream().map(String::trim).filter(s -> !s.isEmpty() && !s.startsWith("#"))
                        .forEach(threatFeed::add);
            }
        } catch (IOException e) {
            log.warn("threat feed not loaded: {}", e.toString());
        }
        log.info("threat feed loaded: {} IPs from {}", threatFeed.size(), feed.toAbsolutePath());
    }

    @PostMapping("/assessments")
    Assessment assess(@RequestBody AssessmentRequest req) {
        List<String> signals = new ArrayList<>();
        boolean compromised = req.ipAddress() != null && threatFeed.contains(req.ipAddress());
        if (compromised) signals.add("IP_ON_THREAT_FEED");
        int h = req.deviceId() == null ? 0 : Math.floorMod(req.deviceId().hashCode(), 100);
        boolean emulator = req.deviceId() != null && req.deviceId().startsWith("D-EMU");
        boolean proxy = req.ipAddress() != null && req.ipAddress().startsWith("10.");
        if (emulator) signals.add("EMULATOR");
        if (proxy) signals.add("ANONYMISING_PROXY");
        int score = compromised ? 90 : emulator ? 80 : proxy ? 60 : 5 + h / 4;
        return new Assessment(UUID.randomUUID().toString(), score, compromised, emulator, proxy, signals);
    }
}
