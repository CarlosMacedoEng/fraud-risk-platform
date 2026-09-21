package com.fraudplatform.fileadapter.ingest;

import com.fraudplatform.fileadapter.config.FileAdapterProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Scheduled scan of {@code inbound/}. Files are processed oldest-name-first (names embed the business date
 * and sequence), one at a time per instance; claims in the database make several instances safe.
 */
@Component
public class FileScanner {

    private static final Logger log = LoggerFactory.getLogger(FileScanner.class);

    private final FileAdapterProperties props;
    private final FileProcessor processor;
    private final io.micrometer.core.instrument.Counter errors;

    public FileScanner(FileAdapterProperties props, FileProcessor processor, io.micrometer.core.instrument.MeterRegistry meters) {
        this.props = props;
        this.processor = processor;
        this.errors = meters.counter("ingestion.scan.errors");
    }

    @Scheduled(fixedDelayString = "${file-adapter.scan-interval-ms:10000}", initialDelay = 2000)
    public void scheduledScan() {
        scan();
    }

    public synchronized List<FileProcessor.Result> scan() {
        List<FileProcessor.Result> results = new ArrayList<>();
        Path inbound = props.dir("inbound");
        try {
            Files.createDirectories(inbound);
            List<Path> files;
            try (Stream<Path> s = Files.list(inbound)) {
                files = s.filter(Files::isRegularFile).filter(p -> !p.getFileName().toString().endsWith(".done"))
                        .filter(p -> !p.getFileName().toString().startsWith(".")).sorted().toList();
            }
            for (Path f : files) {
                try {
                    FileProcessor.Result r = processor.process(f);
                    if (r != null) results.add(r);
                } catch (Exception e) {
                    // The file stays in inbound/ and is retried; the metric makes a silently failing file visible.
                    errors.increment();
                    log.error("unexpected error processing {}", f.getFileName(), e);
                }
            }
        } catch (IOException e) {
            log.error("cannot scan {}", inbound, e);
        }
        return results;
    }
}
