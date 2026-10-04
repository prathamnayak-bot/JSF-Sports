package com.jsf.cricket.ingest;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Keeps the database up to date: downloads Cricsheet datasets (e.g. {@code ipl} -> ipl_json.zip)
 * and imports any matches not already stored. Runs on a schedule when {@code app.sync.enabled=true},
 * and on demand via POST /api/admin/sync.
 */
@Slf4j
@Service
public class CricsheetSync {

    private final CricsheetImporter importer;
    private final boolean enabled;
    private final List<String> datasets;
    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ReentrantLock running = new ReentrantLock();

    public CricsheetSync(CricsheetImporter importer,
                         @Value("${app.sync.enabled}") boolean enabled,
                         @Value("${app.sync.datasets}") List<String> datasets,
                         @Value("${app.sync.base-url}") String baseUrl) {
        this.importer = importer;
        this.enabled = enabled;
        this.datasets = datasets;
        this.baseUrl = baseUrl;
    }

    @Scheduled(cron = "${app.sync.cron}")
    public void scheduledSync() {
        if (enabled) sync();
    }

    /** Downloads and imports every configured dataset; returns one result per dataset. */
    public Map<String, CricsheetImporter.ImportResult> sync() {
        if (!running.tryLock()) throw new IllegalStateException("A sync is already running");
        try {
            Map<String, CricsheetImporter.ImportResult> results = new LinkedHashMap<>();
            for (String dataset : datasets) {
                results.put(dataset, syncDataset(dataset.strip()));
            }
            return results;
        } finally {
            running.unlock();
        }
    }

    private CricsheetImporter.ImportResult syncDataset(String dataset) {
        String url = baseUrl + "/" + dataset + "_json.zip";
        Path zip = null;
        try {
            zip = Files.createTempFile("cricsheet-" + dataset + "-", ".zip");
            HttpResponse<Path> response = http.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofFile(zip));
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " for " + url);
            }
            log.info("Downloaded {} ({} KB)", url, Files.size(zip) / 1024);
            return importer.importZip(zip);
        } catch (IOException e) {
            log.warn("Sync of {} failed", dataset, e);
            return new CricsheetImporter.ImportResult(0, 0, 0, List.of(dataset + ": " + e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CricsheetImporter.ImportResult(0, 0, 0, List.of(dataset + ": interrupted"));
        } finally {
            if (zip != null) {
                try {
                    Files.deleteIfExists(zip);
                } catch (IOException e) {
                    log.debug("Could not delete {}", zip, e);
                }
            }
        }
    }
}
