package com.fraudplatform.fileadapter.api;

import com.fraudplatform.fileadapter.ingest.FileProcessor;
import com.fraudplatform.fileadapter.ingest.FileScanner;
import com.fraudplatform.fileadapter.persistence.RunRepository;
import com.fraudplatform.fileadapter.recon.ReconciliationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Operations API for support engineers: runs, reports, quarantined records, reconciliation results. */
@RestController
@RequestMapping("/v1")
public class IngestionController {

    private final RunRepository runs;
    private final FileScanner scanner;
    private final ReconciliationService reconciliation;

    public IngestionController(RunRepository runs, FileScanner scanner, ReconciliationService reconciliation) {
        this.runs = runs;
        this.scanner = scanner;
        this.reconciliation = reconciliation;
    }

    @GetMapping("/ingestion/runs")
    public List<RunRepository.Run> runs(@RequestParam(required = false) String tenant, @RequestParam(defaultValue = "50") int limit) {
        return runs.recent(tenant, Math.min(limit, 500));
    }

    @GetMapping("/ingestion/runs/{runId}")
    public ResponseEntity<Map<String, Object>> run(@PathVariable UUID runId) {
        return runs.find(runId).<ResponseEntity<Map<String, Object>>>map(r -> ResponseEntity.ok(Map.of(
                        "run", r, "quarantinedSample", runs.quarantined(runId, 50))))
                .orElse(ResponseEntity.notFound().build());
    }

    /** Trigger an immediate scan (normally scheduled). */
    @PostMapping("/ingestion/scan")
    public List<FileProcessor.Result> scan() {
        return scanner.scan();
    }

    @GetMapping("/reconciliation/{tenant}/{date}")
    public List<Map<String, Object>> reconciliation(@PathVariable String tenant, @PathVariable LocalDate date,
                                                    @RequestParam(required = false) String category) {
        return reconciliation.results(tenant, date, category);
    }
}
