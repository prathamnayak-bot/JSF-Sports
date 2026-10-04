package com.jsf.cricket.fixtures;

import com.jsf.cricket.ingest.CricsheetImporter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps the fixture table filled from the CricketData.org API: finds the newest series matching
 * {@code app.fixtures.series} that has fixtures, and stores its match list. 2 API calls per refresh.
 */
@Slf4j
@Service
public class FixtureService {

    private static final Pattern YEAR = Pattern.compile("(20\\d{2})");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final RestClient http;
    private final String apiKey;
    private final String series;

    public FixtureService(JdbcTemplate jdbc, TransactionTemplate tx, RestClient.Builder builder,
                          @Value("${app.fixtures.base-url}") String baseUrl,
                          @Value("${app.fixtures.api-key}") String apiKey,
                          @Value("${app.fixtures.series}") String series) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.http = builder.baseUrl(baseUrl).build();
        this.apiKey = apiKey;
        this.series = series;
    }

    public record Fixture(String id, String seriesName, String name, LocalDateTime startTimeGmt, String venue,
                          String team1, String team2, Long team1Id, Long team2Id, Long venueId,
                          String status, boolean started, boolean ended) {
    }

    public record RefreshResult(String series, int fixtures) {
    }

    public boolean enabled() {
        return StringUtils.hasText(apiKey);
    }

    /** Fill the table on first start (e.g. every restart with the in-memory H2 database). */
    @EventListener(ApplicationReadyEvent.class)
    public void refreshIfEmpty() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM fixture", Integer.class);
        if (enabled() && count != null && count == 0) {
            try {
                refresh();
            } catch (RuntimeException e) {
                log.warn("Initial fixture refresh failed: {}", e.getMessage());
            }
        }
    }

    @Scheduled(cron = "${app.fixtures.cron}")
    public void scheduledRefresh() {
        if (enabled()) refresh();
    }

    public RefreshResult refresh() {
        if (!enabled()) throw new IllegalStateException("Set CRICKETDATA_API_KEY in .env to load fixtures");

        JsonNode found = call("/series?apikey={key}&offset=0&search={search}", apiKey, series);
        List<JsonNode> candidates = new ArrayList<>();
        found.path("data").forEach(candidates::add);
        JsonNode newest = candidates.stream()
                .filter(s -> s.path("matches").asInt() > 0)
                .max(Comparator.comparingInt(s -> year(s.path("name").asString())))
                .orElseThrow(() -> new IllegalStateException("No series with fixtures found for '" + series + "'"));

        JsonNode info = call("/series_info?apikey={key}&id={id}", apiKey, newest.path("id").asString());
        String seriesName = info.path("data").path("info").path("name").asString(newest.path("name").asString());
        List<Fixture> fixtures = new ArrayList<>();
        info.path("data").path("matchList").forEach(m -> parse(m, seriesName).ifPresent(fixtures::add));
        save(fixtures);
        log.info("Loaded {} fixtures for {}", fixtures.size(), seriesName);
        return new RefreshResult(seriesName, fixtures.size());
    }

    /** Upcoming fixtures (soonest first), or - when none are scheduled - the most recent ones. */
    public List<Fixture> list(boolean upcoming, int limit) {
        String where = upcoming ? "WHERE NOT match_ended ORDER BY start_time" : "WHERE match_ended ORDER BY start_time DESC";
        return jdbc.query("""
                SELECT id, series_name, name, start_time, venue_name, team1_name, team2_name,
                       team1_id, team2_id, venue_id, status, match_started, match_ended
                FROM fixture %s LIMIT ?""".formatted(where), (rs, i) -> new Fixture(
                rs.getString("id"), rs.getString("series_name"), rs.getString("name"),
                rs.getTimestamp("start_time").toLocalDateTime(), rs.getString("venue_name"),
                rs.getString("team1_name"), rs.getString("team2_name"),
                rs.getObject("team1_id", Long.class), rs.getObject("team2_id", Long.class),
                rs.getObject("venue_id", Long.class), rs.getString("status"),
                rs.getBoolean("match_started"), rs.getBoolean("match_ended")), limit);
    }

    private JsonNode call(String uri, Object... vars) {
        try {
            JsonNode body = http.get().uri(uri, vars).retrieve().body(JsonNode.class);
            if (body == null || !"success".equals(body.path("status").asString())) {
                // never log the whole body: the API echoes the key back
                String reason = body == null ? "empty response" : body.path("reason").asString(body.path("status").asString());
                throw new IllegalStateException("CricketData.org request failed: " + reason);
            }
            return body;
        } catch (RestClientResponseException e) {
            // status only: the URL and the response body both contain the API key
            throw new IllegalStateException("CricketData.org request failed: HTTP " + e.getStatusCode().value());
        } catch (RestClientException e) {
            throw new IllegalStateException("CricketData.org request failed: "
                    + e.getMostSpecificCause().getClass().getSimpleName());
        }
    }

    private Optional<Fixture> parse(JsonNode m, String seriesName) {
        JsonNode teams = m.path("teams");
        if (teams.size() < 2 || !m.hasNonNull("dateTimeGMT")) return Optional.empty();
        String venue = m.path("venue").asString(null);
        return Optional.of(new Fixture(m.path("id").asString(), seriesName, m.path("name").asString(),
                LocalDateTime.parse(m.path("dateTimeGMT").asString()), venue,
                teams.path(0).asString(), teams.path(1).asString(), null, null, null,
                m.path("status").asString(null), m.path("matchStarted").asBoolean(false),
                m.path("matchEnded").asBoolean(false)));
    }

    private void save(List<Fixture> fixtures) {
        Map<String, Long> teamIds = idsByName("SELECT name, id FROM team");
        Map<String, Long> venueIds = idsByName("SELECT name, id FROM venue");
        List<Object[]> rows = fixtures.stream().map(f -> new Object[]{
                f.id(), f.seriesName(), f.name(), Timestamp.valueOf(f.startTimeGmt()), f.venue(),
                f.team1(), f.team2(), teamIds.get(f.team1()), teamIds.get(f.team2()),
                f.venue() == null ? null : venueIds.get(CricsheetImporter.canonicalVenue(f.venue())),
                f.status(), f.started(), f.ended()}).toList();
        tx.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM fixture WHERE series_name = ?", fixtures.isEmpty() ? "" : fixtures.getFirst().seriesName());
            jdbc.batchUpdate("""
                    INSERT INTO fixture (id, series_name, name, start_time, venue_name, team1_name, team2_name,
                                         team1_id, team2_id, venue_id, status, match_started, match_ended)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", rows);
        });
    }

    private Map<String, Long> idsByName(String sql) {
        Map<String, Long> map = new HashMap<>();
        jdbc.query(sql, rs -> {
            map.put(rs.getString(1), rs.getLong(2));
        });
        return map;
    }

    private static int year(String seriesName) {
        Matcher m = YEAR.matcher(seriesName);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }
}
