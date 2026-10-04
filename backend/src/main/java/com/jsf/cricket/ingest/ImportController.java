package com.jsf.cricket.ingest;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/** Local-development endpoints to load Cricsheet data (from the server's disk, or downloaded fresh). */
@RestController
@Tag(name = "Admin", description = "Load Cricsheet data (local development)")
@RequestMapping("/api/admin")
public class ImportController {

    private final CricsheetImporter importer;
    private final CricsheetSync sync;

    public ImportController(CricsheetImporter importer, CricsheetSync sync) {
        this.importer = importer;
        this.sync = sync;
    }

    public record ImportRequest(@NotBlank String directory) {
    }

    @PostMapping("/import")
    public CricsheetImporter.ImportResult importDirectory(@RequestBody @Validated ImportRequest request) throws IOException {
        return importer.importDirectory(Path.of(request.directory()));
    }

    /** Download the configured Cricsheet datasets now and import new matches. */
    @PostMapping("/sync")
    public Map<String, CricsheetImporter.ImportResult> syncNow() {
        return sync.sync();
    }
}
