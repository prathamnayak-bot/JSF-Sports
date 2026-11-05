package com.jsf.cricket.config;

import org.apache.commons.logging.Log;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Accepts the database address in the forms people actually paste into hosting dashboards:
 * the JDBC form ({@code jdbc:postgresql://host/db}), the form Neon/Render/Heroku show
 * ({@code postgresql://user:password@host/db?sslmode=require}), with stray quotes, spaces or a leading
 * {@code DB_URL=}. Converts it to a JDBC URL before the DataSource is created, and logs the host it uses
 * (never the password). Registered in META-INF/spring.factories.
 */
public class DatabaseUrlNormalizer implements EnvironmentPostProcessor, Ordered {

    static final String[] URL_PROPERTIES = {"spring.datasource.url", "app.chat.db.url"};

    private final Log log;

    public DatabaseUrlNormalizer(DeferredLogFactory logs) {
        this.log = logs.getLog(DatabaseUrlNormalizer.class);
    }

    /** Runs after application.properties and .env have been loaded. */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        Map<String, Object> fixed = new LinkedHashMap<>();
        for (String property : URL_PROPERTIES) {
            String raw = env.getProperty(property);
            if (raw == null || raw.isBlank()) continue;
            Normalized n = normalize(raw);
            if (!n.url().equals(raw)) fixed.put(property, n.url());
            // credentials inside a postgresql:// URL belong to the main connection only
            if (property.equals("spring.datasource.url") && n.username() != null) {
                fixed.put("spring.datasource.username", n.username());
                if (n.password() != null) fixed.put("spring.datasource.password", n.password());
            }
        }
        if (!fixed.isEmpty()) {
            env.getPropertySources().addFirst(new MapPropertySource("normalizedDatabaseUrl", fixed));
        }
        String url = env.getProperty("spring.datasource.url", "");
        log.info("Database: " + describe(url) + " as user '" + env.getProperty("spring.datasource.username", "") + "'");
        if (!url.startsWith("jdbc:")) {
            log.warn("spring.datasource.url / DB_URL does not look like a database address: it should start with "
                    + "jdbc:postgresql:// or postgresql://");
        }
    }

    record Normalized(String url, String username, String password) {
    }

    static Normalized normalize(String raw) {
        String s = unquote(raw.strip());
        // value pasted together with its name, e.g. "DB_URL=jdbc:postgresql://..."
        if (s.matches("^[A-Za-z_][A-Za-z0-9_]*=.*") && !s.startsWith("jdbc:")) {
            s = unquote(s.substring(s.indexOf('=') + 1).strip());
        }
        if (s.startsWith("postgres://") || s.startsWith("postgresql://")) {
            URI uri = URI.create(s);
            String username = null;
            String password = null;
            if (uri.getUserInfo() != null) {
                String[] parts = uri.getUserInfo().split(":", 2);
                username = parts[0];
                password = parts.length > 1 ? parts[1] : null;
            }
            StringBuilder jdbc = new StringBuilder("jdbc:postgresql://").append(uri.getHost());
            if (uri.getPort() > 0) jdbc.append(':').append(uri.getPort());
            jdbc.append(uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath());
            String query = withoutChannelBinding(uri.getRawQuery());
            if (!query.isEmpty()) jdbc.append('?').append(query);
            return new Normalized(jdbc.toString(), username, password);
        }
        return new Normalized(s, null, null);
    }

    /** libpq's channel_binding option is not a JDBC driver option. */
    private static String withoutChannelBinding(String query) {
        if (query == null) return "";
        return Arrays.stream(query.split("&"))
                .filter(p -> !p.isEmpty() && !p.startsWith("channel_binding="))
                .collect(Collectors.joining("&"));
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && (s.startsWith("\"") && s.endsWith("\"") || s.startsWith("'") && s.endsWith("'"))) {
            return s.substring(1, s.length() - 1).strip();
        }
        return s;
    }

    /** host/database part of a JDBC URL, without credentials or parameters, for the log. */
    static String describe(String url) {
        String s = url.replaceFirst("^jdbc:postgresql://", "").replaceFirst("\\?.*$", "");
        return s.replaceFirst("^[^@/]*@", "");
    }
}
