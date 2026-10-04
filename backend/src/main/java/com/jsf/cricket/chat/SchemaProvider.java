package com.jsf.cricket.chat;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Supplies schema.sql (tables + explanatory comments) as context for text-to-SQL. */
@Component
public class SchemaProvider {

    private final String schema;

    public SchemaProvider() {
        try {
            schema = new ClassPathResource("schema.sql").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String schema() {
        return schema;
    }
}
