package com.fraudplatform.decision.application;

import com.fraudplatform.decision.config.PlatformProperties;
import com.fraudplatform.decision.persistence.StrategyRepository;
import com.fraudplatform.decision.strategy.StrategyCompiler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Imports version-controlled strategy files ({@code config/customers/<tenant>/strategies/*.json}) into
 * the database as VALIDATED versions, and applies {@code environments/<env>.json} as the initial
 * deployment when none exists. Existing versions are never overwritten (versions are immutable; a
 * changed file with an existing version number is reported as drift).
 */
public class StrategyBootstrap {

    private static final Logger log = LoggerFactory.getLogger(StrategyBootstrap.class);

    private final StrategyRepository repository;
    private final StrategyCompiler compiler;
    private final ObjectMapper json;
    private final PlatformProperties props;

    public StrategyBootstrap(StrategyRepository repository, StrategyCompiler compiler, ObjectMapper json, PlatformProperties props) {
        this.repository = repository;
        this.compiler = compiler;
        this.json = json;
        this.props = props;
    }

    public void run() throws IOException {
        Path customers = props.configDir().resolve("customers");
        if (!Files.isDirectory(customers)) {
            log.warn("strategy bootstrap skipped: {} not found", customers.toAbsolutePath());
            return;
        }
        try (Stream<Path> tenants = Files.list(customers)) {
            for (Path tenantDir : tenants.filter(Files::isDirectory).sorted().toList()) {
                importTenant(tenantDir.getFileName().toString(), tenantDir);
            }
        }
    }

    private void importTenant(String tenant, Path dir) throws IOException {
        Path strategies = dir.resolve("strategies");
        if (Files.isDirectory(strategies)) {
            List<Path> files;
            try (Stream<Path> s = Files.list(strategies)) {
                files = s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
            }
            for (Path file : files) {
                JsonNode doc = json.readTree(Files.readString(file));
                String version = doc.path("version").asString();
                String checksum = StrategyCompiler.checksum(doc);
                var existing = repository.findVersion(tenant, version);
                if (existing.isPresent()) {
                    if (!existing.get().checksum().equals(checksum)) {
                        log.warn("strategy drift tenant={} version={}: file differs from stored version (not overwritten)",
                                tenant, version);
                    }
                    continue;
                }
                compiler.compile(tenant, doc); // refuse to import an invalid file
                repository.insertVersion(tenant, version, doc.toString(), checksum, "VALIDATED",
                        doc.path("changeSummary").asString(null), "bootstrap:" + file.getFileName());
                log.info("strategy imported tenant={} version={}", tenant, version);
            }
        }
        Path envFile = dir.resolve("environments").resolve(props.environment() + ".json");
        if (Files.exists(envFile) && repository.findDeployment(tenant, props.environment()).isEmpty()) {
            String active = json.readTree(Files.readString(envFile)).path("activeStrategyVersion").asString();
            repository.deploy(tenant, props.environment(), active, "bootstrap", null);
            log.info("initial deployment tenant={} env={} version={}", tenant, props.environment(), active);
        }
    }
}
