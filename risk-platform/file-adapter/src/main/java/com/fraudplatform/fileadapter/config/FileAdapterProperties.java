package com.fraudplatform.fileadapter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.Set;

/**
 * Local layout under {@code baseDir}: inbound/ processing/ archive/ rejected/ quarantine/ reports/ outbound/.
 * In AWS each directory maps to an S3 prefix and scanning is replaced by S3 event notifications → SQS.
 */
@ConfigurationProperties(prefix = "file-adapter")
public record FileAdapterProperties(Path baseDir, long scanIntervalMs, int staleRunMinutes, Set<String> tenants, String opsKeySha256) {

    public Path dir(String name) {
        return baseDir.resolve(name);
    }
}
