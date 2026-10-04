package com.jsf.cricket.ingest;

import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Path;

/** Local-development endpoint to load an unzipped Cricsheet download from the server's disk. */
@RestController
@RequestMapping("/api/admin")
public class ImportController {

    private final CricsheetImporter importer;

    public ImportController(CricsheetImporter importer) {
        this.importer = importer;
    }

    public record ImportRequest(@NotBlank String directory) {
    }

    @PostMapping("/import")
    public CricsheetImporter.ImportResult importDirectory(@RequestBody @Validated ImportRequest request) throws IOException {
        return importer.importDirectory(Path.of(request.directory()));
    }
}
