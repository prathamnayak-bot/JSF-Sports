package com.jsf.cricket.ingest;

import com.jsf.cricket.domain.CricketMatch;
import com.jsf.cricket.domain.Innings;
import com.jsf.cricket.domain.Player;
import com.jsf.cricket.domain.Team;
import com.jsf.cricket.domain.Venue;
import com.jsf.cricket.repository.CricketMatchRepository;
import com.jsf.cricket.repository.InningsRepository;
import com.jsf.cricket.repository.PlayerRepository;
import com.jsf.cricket.repository.TeamRepository;
import com.jsf.cricket.repository.VenueRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Imports Cricsheet match files (JSON format, https://cricsheet.org/format/json/) into the database.
 * Each file is imported in its own transaction; files already imported are skipped.
 */
@Slf4j
@Service
public class CricsheetImporter {

    /** Dismissals that are not credited to the bowler and/or don't count as a wicket. */
    private static final Set<String> NOT_A_WICKET = Set.of("retired hurt", "retired not out");

    /** Renamed franchises, stored under their current name so their history stays together. */
    private static final Map<String, String> TEAM_RENAMES = Map.of(
            "Delhi Daredevils", "Delhi Capitals",
            "Kings XI Punjab", "Punjab Kings",
            "Royal Challengers Bangalore", "Royal Challengers Bengaluru",
            "Rising Pune Supergiants", "Rising Pune Supergiant");

    private static final String INSERT_DELIVERY = """
            INSERT INTO delivery (innings_id, over_number, ball_in_over, batter_id, bowler_id, non_striker_id,
                                  runs_batter, runs_extras, runs_total, extra_type, is_legal_ball,
                                  wicket_kind, player_out_id, fielder_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";

    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final TeamRepository teams;
    private final PlayerRepository players;
    private final VenueRepository venues;
    private final CricketMatchRepository matches;
    private final InningsRepository inningsRepo;

    public CricsheetImporter(ObjectMapper mapper, JdbcTemplate jdbc, TransactionTemplate tx,
                             TeamRepository teams, PlayerRepository players, VenueRepository venues,
                             CricketMatchRepository matches, InningsRepository inningsRepo) {
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.tx = tx;
        this.teams = teams;
        this.players = players;
        this.venues = venues;
        this.matches = matches;
        this.inningsRepo = inningsRepo;
    }

    public record ImportResult(int imported, int skipped, int failed, List<String> errors) {
    }

    /** Imports every *.json file in a folder (e.g. an unzipped Cricsheet download). */
    public ImportResult importDirectory(Path dir) throws IOException {
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList();
        }
        Tally tally = new Tally();
        for (Path file : files) {
            tally.run(file.getFileName().toString(), () -> importFile(file));
        }
        log.info("Cricsheet import from {}: {}", dir, tally);
        return tally.result();
    }

    /** Imports the match files straight out of a Cricsheet zip download, without unzipping to disk. */
    public ImportResult importZip(Path zip) throws IOException {
        Tally tally = new Tally();
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            for (ZipEntry entry : Collections.list(zf.entries())) {
                String name = Path.of(entry.getName()).getFileName().toString();
                if (entry.isDirectory() || !name.endsWith(".json")) continue;
                tally.run(name, () -> {
                    String cricsheetId = name.replaceFirst("\\.json$", "");
                    if (matches.existsByCricsheetId(cricsheetId)) return false;
                    try (InputStream in = zf.getInputStream(entry)) {
                        importJson(cricsheetId, mapper.readTree(in));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    return true;
                });
            }
        }
        log.info("Cricsheet import from {}: {}", zip.getFileName(), tally);
        return tally.result();
    }

    /** Counts imported / skipped / failed files and keeps the first few error messages. */
    private static class Tally {
        int imported, skipped, failed;
        final List<String> errors = new ArrayList<>();

        void run(String name, Supplier<Boolean> importOne) {
            try {
                if (importOne.get()) imported++;
                else skipped++;
            } catch (RuntimeException e) {
                failed++;
                if (errors.size() < 20) errors.add(name + ": " + e.getMessage());
                log.warn("Failed to import {}", name, e);
            }
        }

        ImportResult result() {
            return new ImportResult(imported, skipped, failed, errors);
        }

        @Override
        public String toString() {
            return imported + " imported, " + skipped + " skipped, " + failed + " failed";
        }
    }

    /** Imports one match file. Returns false if the match was already in the database. */
    public boolean importFile(Path file) {
        String cricsheetId = file.getFileName().toString().replaceFirst("\\.json$", "");
        if (matches.existsByCricsheetId(cricsheetId)) return false;
        importJson(cricsheetId, mapper.readTree(file.toFile()));
        return true;
    }

    private void importJson(String cricsheetId, JsonNode root) {
        tx.executeWithoutResult(status -> importMatch(cricsheetId, root));
    }

    private void importMatch(String cricsheetId, JsonNode root) {
        JsonNode info = root.path("info");
        People people = new People(info);

        List<String> teamNames = new ArrayList<>();
        info.path("teams").forEach(t -> teamNames.add(t.asString()));
        Map<String, Team> teamByName = new HashMap<>();
        teamNames.forEach(n -> teamByName.put(n, team(n)));

        CricketMatch m = new CricketMatch();
        m.setCricsheetId(cricsheetId);
        m.setFormat(info.path("match_type").asString().toUpperCase());
        m.setEventName(textOrNull(info.path("event").path("name")));
        m.setSeason(textOrNull(info.path("season")));
        m.setMatchDate(LocalDate.parse(info.path("dates").path(0).asString()));
        if (info.hasNonNull("venue")) m.setVenue(venue(info.get("venue").asString(), textOrNull(info.path("city"))));
        m.setTeam1(teamByName.get(teamNames.get(0)));
        m.setTeam2(teamByName.get(teamNames.get(1)));
        m.setTossWinner(teamByName.get(textOrNull(info.path("toss").path("winner"))));
        m.setTossDecision(textOrNull(info.path("toss").path("decision")));

        JsonNode outcome = info.path("outcome");
        m.setWinner(teamByName.get(textOrNull(outcome.path("winner"))));
        for (String type : List.of("runs", "wickets", "innings")) {
            if (outcome.path("by").has(type)) {
                m.setResultType(type);
                m.setResultMargin(outcome.path("by").path(type).asInt());
            }
        }
        if (info.path("player_of_match").size() > 0) {
            m.setPlayerOfMatch(people.get(info.path("player_of_match").path(0).asString()));
        }
        matches.save(m);

        List<Object[]> lineup = new ArrayList<>();
        for (Map.Entry<String, JsonNode> e : info.path("players").properties()) {
            Team team = teamByName.get(e.getKey());
            for (JsonNode name : e.getValue()) {
                lineup.add(new Object[]{m.getId(), team.getId(), people.get(name.asString()).getId()});
            }
        }
        jdbc.batchUpdate("INSERT INTO match_player (match_id, team_id, player_id) VALUES (?, ?, ?)", lineup);

        int number = 0;
        for (JsonNode inn : root.path("innings")) {
            if (inn.path("super_over").asBoolean(false)) continue; // keep regular-play stats clean
            Team batting = teamByName.get(inn.path("team").asString());
            Team bowling = batting == m.getTeam1() ? m.getTeam2() : m.getTeam1();
            importInnings(m, ++number, batting, bowling, inn, people);
        }
    }

    private void importInnings(CricketMatch m, int number, Team batting, Team bowling,
                               JsonNode inn, People people) {
        Innings innings = new Innings();
        innings.setMatch(m);
        innings.setInningsNumber(number);
        innings.setBattingTeam(batting);
        innings.setBowlingTeam(bowling);
        inningsRepo.save(innings);

        int runs = 0, wickets = 0, legalBalls = 0;
        List<Object[]> rows = new ArrayList<>();
        for (JsonNode over : inn.path("overs")) {
            int overNumber = over.path("over").asInt();
            int ballInOver = 0;
            for (JsonNode d : over.path("deliveries")) {
                JsonNode extras = d.path("extras");
                String extraType = extras.isObject() && !extras.isEmpty() ? extras.propertyNames().iterator().next() : null;
                boolean legal = !extras.has("wides") && !extras.has("noballs");

                String wicketKind = null;
                Long playerOutId = null;
                Long fielderId = null;
                JsonNode wicket = d.path("wickets").path(0);
                if (!wicket.isMissingNode()) {
                    wicketKind = wicket.path("kind").asString();
                    playerOutId = people.get(wicket.path("player_out").asString()).getId();
                    fielderId = fielderId(wicket, wicketKind, d, people);
                    if (!NOT_A_WICKET.contains(wicketKind)) wickets += d.path("wickets").size();
                }

                int total = d.path("runs").path("total").asInt();
                runs += total;
                if (legal) legalBalls++;
                rows.add(new Object[]{
                        innings.getId(), overNumber, ++ballInOver,
                        people.get(d.path("batter").asString()).getId(),
                        people.get(d.path("bowler").asString()).getId(),
                        people.get(d.path("non_striker").asString()).getId(),
                        d.path("runs").path("batter").asInt(), d.path("runs").path("extras").asInt(), total,
                        extraType, legal, wicketKind, playerOutId, fielderId});
            }
        }
        jdbc.batchUpdate(INSERT_DELIVERY, rows);

        innings.setTotalRuns(runs);
        innings.setTotalWickets(wickets);
        innings.setLegalBalls(legalBalls);
    }

    /** The fielder credited with a dismissal; substitutes are skipped (they aren't in the playing XI). */
    private static Long fielderId(JsonNode wicket, String kind, JsonNode delivery, People people) {
        if ("caught and bowled".equals(kind)) return people.get(delivery.path("bowler").asString()).getId();
        JsonNode fielder = wicket.path("fielders").path(0);
        if (fielder.isMissingNode() || fielder.path("substitute").asBoolean(false) || !fielder.has("name")) return null;
        Player p = people.find(fielder.path("name").asString());
        return p == null ? null : p.getId();
    }

    /**
     * Maps names used in one match file to Player rows. Cricsheet's registry gives each name a stable id;
     * players are created lazily so officials listed in the registry don't end up in the player table.
     */
    private class People {
        private final Map<String, String> idByName = new HashMap<>();
        private final Map<String, Player> cache = new HashMap<>();

        People(JsonNode info) {
            for (Map.Entry<String, JsonNode> e : info.path("registry").path("people").properties()) {
                idByName.put(e.getKey(), e.getValue().asString());
            }
        }

        /** Like {@link #get} but returns null for names missing from the registry. */
        Player find(String name) {
            return idByName.containsKey(name) ? get(name) : null;
        }

        Player get(String name) {
            return cache.computeIfAbsent(name, n -> {
                String id = idByName.get(n);
                if (id == null) throw new IllegalStateException("Player not in registry: " + n);
                return players.findByCricsheetId(id).orElseGet(() -> players.save(new Player(id, n)));
            });
        }
    }

    private Team team(String nameInFile) {
        String name = TEAM_RENAMES.getOrDefault(nameInFile, nameInFile);
        return teams.findByName(name).orElseGet(() -> teams.save(new Team(name)));
    }

    private Venue venue(String name, String city) {
        return venues.findByName(name).orElseGet(() -> venues.save(new Venue(name, city)));
    }

    private static String textOrNull(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? null : node.asString();
    }
}
