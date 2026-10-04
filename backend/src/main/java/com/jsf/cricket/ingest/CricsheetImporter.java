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
import java.util.HashSet;
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

    /** Renamed grounds (after {@link #canonicalVenue} clean-up), stored under their current name. */
    private static final Map<String, String> VENUE_RENAMES = Map.of(
            "Feroz Shah Kotla", "Arun Jaitley Stadium",
            "Sardar Patel Stadium", "Narendra Modi Stadium",
            "Punjab Cricket Association Stadium", "Punjab Cricket Association IS Bindra Stadium",
            "Sheikh Zayed Stadium", "Zayed Cricket Stadium",
            "Subrata Roy Sahara Stadium", "Maharashtra Cricket Association Stadium");

    private static final Map<String, String> CITY_RENAMES = Map.of("Bangalore", "Bengaluru");

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
        Lookup lookup = new Lookup();
        for (Path file : files) {
            String cricsheetId = file.getFileName().toString().replaceFirst("\\.json$", "");
            tally.run(file.getFileName().toString(),
                    () -> importIfNew(cricsheetId, () -> mapper.readTree(file.toFile()), lookup));
        }
        log.info("Cricsheet import from {}: {}", dir, tally);
        return tally.result();
    }

    /** Imports the match files straight out of a Cricsheet zip download, without unzipping to disk. */
    public ImportResult importZip(Path zip) throws IOException {
        Tally tally = new Tally();
        Lookup lookup = new Lookup();
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            for (ZipEntry entry : Collections.list(zf.entries())) {
                String name = Path.of(entry.getName()).getFileName().toString();
                if (entry.isDirectory() || !name.endsWith(".json")) continue;
                String cricsheetId = name.replaceFirst("\\.json$", "");
                tally.run(name, () -> importIfNew(cricsheetId, () -> {
                    try (InputStream in = zf.getInputStream(entry)) {
                        return mapper.readTree(in);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }, lookup));
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

    /**
     * Imports one match (in its own transaction) unless it is already stored. Returns false if skipped.
     * The JSON is only parsed for new matches.
     */
    private boolean importIfNew(String cricsheetId, Supplier<JsonNode> json, Lookup lookup) {
        if (lookup.knownMatches.contains(cricsheetId)) return false;
        JsonNode root = json.get();
        try {
            tx.executeWithoutResult(status -> importMatch(cricsheetId, root, lookup));
            lookup.commit(cricsheetId);
        } catch (RuntimeException e) {
            lookup.rollback();
            throw e;
        }
        return true;
    }

    private void importMatch(String cricsheetId, JsonNode root, Lookup lookup) {
        JsonNode info = root.path("info");
        People people = new People(info, lookup);

        List<String> teamNames = new ArrayList<>();
        info.path("teams").forEach(t -> teamNames.add(t.asString()));
        Map<String, Team> teamByName = new HashMap<>();
        teamNames.forEach(n -> teamByName.put(n, team(n, lookup)));

        CricketMatch m = new CricketMatch();
        m.setCricsheetId(cricsheetId);
        m.setFormat(info.path("match_type").asString().toUpperCase());
        m.setEventName(textOrNull(info.path("event").path("name")));
        m.setSeason(textOrNull(info.path("season")));
        m.setMatchDate(LocalDate.parse(info.path("dates").path(0).asString()));
        if (info.hasNonNull("venue")) {
            m.setVenue(venue(info.get("venue").asString(), textOrNull(info.path("city")), lookup));
        }
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
            m.setPlayerOfMatch(players.getReferenceById(people.id(info.path("player_of_match").path(0).asString())));
        }
        matches.save(m);

        List<Object[]> lineup = new ArrayList<>();
        for (Map.Entry<String, JsonNode> e : info.path("players").properties()) {
            Team team = teamByName.get(e.getKey());
            for (JsonNode name : e.getValue()) {
                lineup.add(new Object[]{m.getId(), team.getId(), people.id(name.asString())});
            }
        }
        jdbc.batchUpdate("INSERT INTO match_player (match_id, team_id, player_id) VALUES (?, ?, ?)", lineup);

        int number = 0;
        for (JsonNode inn : root.path("innings")) {
            if (inn.path("super_over").asBoolean(false)) continue; // keep regular-play stats clean
            Team batting = teamByName.get(inn.path("team").asString());
            Team bowling = batting.getId().equals(m.getTeam1().getId()) ? m.getTeam2() : m.getTeam1();
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
                    playerOutId = people.id(wicket.path("player_out").asString());
                    fielderId = fielderId(wicket, wicketKind, d, people);
                    if (!NOT_A_WICKET.contains(wicketKind)) wickets += d.path("wickets").size();
                }

                int total = d.path("runs").path("total").asInt();
                runs += total;
                if (legal) legalBalls++;
                rows.add(new Object[]{
                        innings.getId(), overNumber, ++ballInOver,
                        people.id(d.path("batter").asString()),
                        people.id(d.path("bowler").asString()),
                        people.id(d.path("non_striker").asString()),
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
        if ("caught and bowled".equals(kind)) return people.id(delivery.path("bowler").asString());
        JsonNode fielder = wicket.path("fielders").path(0);
        if (fielder.isMissingNode() || fielder.path("substitute").asBoolean(false) || !fielder.has("name")) return null;
        return people.find(fielder.path("name").asString());
    }

    /**
     * Ids of the players, teams, venues and matches already stored, loaded once per import run so each match
     * file doesn't need ~30 lookup queries. Rows created while importing a file are kept aside and only added
     * once that file's transaction commits (a failed file rolls its new rows back).
     */
    private class Lookup {
        final Set<String> knownMatches = new HashSet<>(
                jdbc.queryForList("SELECT cricsheet_id FROM cricket_match WHERE cricsheet_id IS NOT NULL", String.class));
        final Map<String, Long> playerIds = load("SELECT cricsheet_id, id FROM player WHERE cricsheet_id IS NOT NULL");
        final Map<String, Long> teamIds = load("SELECT name, id FROM team");
        final Map<String, Long> venueIds = load("SELECT name, id FROM venue");
        final Map<String, Long> newPlayers = new HashMap<>(), newTeams = new HashMap<>(), newVenues = new HashMap<>();

        private Map<String, Long> load(String sql) {
            Map<String, Long> map = new HashMap<>();
            jdbc.query(sql, rs -> {
                map.put(rs.getString(1), rs.getLong(2));
            });
            return map;
        }

        Long player(String cricsheetId) {
            Long id = playerIds.get(cricsheetId);
            return id != null ? id : newPlayers.get(cricsheetId);
        }

        Long team(String name) {
            Long id = teamIds.get(name);
            return id != null ? id : newTeams.get(name);
        }

        Long venue(String name) {
            Long id = venueIds.get(name);
            return id != null ? id : newVenues.get(name);
        }

        void commit(String cricsheetId) {
            knownMatches.add(cricsheetId);
            playerIds.putAll(newPlayers);
            teamIds.putAll(newTeams);
            venueIds.putAll(newVenues);
            rollback();
        }

        void rollback() {
            newPlayers.clear();
            newTeams.clear();
            newVenues.clear();
        }
    }

    /**
     * Maps names used in one match file to player ids. Cricsheet's registry gives each name a stable id;
     * players are created lazily so officials listed in the registry don't end up in the player table.
     */
    private class People {
        private final Map<String, String> cricsheetIdByName = new HashMap<>();
        private final Lookup lookup;

        People(JsonNode info, Lookup lookup) {
            this.lookup = lookup;
            for (Map.Entry<String, JsonNode> e : info.path("registry").path("people").properties()) {
                cricsheetIdByName.put(e.getKey(), e.getValue().asString());
            }
        }

        /** Like {@link #id} but returns null for names missing from the registry. */
        Long find(String name) {
            return cricsheetIdByName.containsKey(name) ? id(name) : null;
        }

        Long id(String name) {
            String cricsheetId = cricsheetIdByName.get(name);
            if (cricsheetId == null) throw new IllegalStateException("Player not in registry: " + name);
            Long id = lookup.player(cricsheetId);
            if (id == null) {
                id = players.save(new Player(cricsheetId, name)).getId();
                lookup.newPlayers.put(cricsheetId, id);
            }
            return id;
        }
    }

    private Team team(String nameInFile, Lookup lookup) {
        String name = TEAM_RENAMES.getOrDefault(nameInFile, nameInFile);
        Long id = lookup.team(name);
        if (id != null) return teams.getReferenceById(id);
        Team team = teams.save(new Team(name));
        lookup.newTeams.put(name, team.getId());
        return team;
    }

    private Venue venue(String nameInFile, String cityInFile, Lookup lookup) {
        String name = canonicalVenue(nameInFile);
        Long id = lookup.venue(name);
        if (id != null) return venues.getReferenceById(id);
        String city = cityInFile == null ? null : CITY_RENAMES.getOrDefault(cityInFile, cityInFile);
        Venue venue = venues.save(new Venue(name, city));
        lookup.newVenues.put(name, venue.getId());
        return venue;
    }

    /**
     * Cricsheet spells grounds several ways ("Wankhede Stadium", "Wankhede Stadium, Mumbai",
     * "M.Chinnaswamy Stadium"...). Keep the part before the first comma, tidy initials, apply renames.
     */
    static String canonicalVenue(String name) {
        String base = name.split(",")[0].replace(".", " ").replaceAll("\\s+", " ").strip();
        return VENUE_RENAMES.getOrDefault(base, base);
    }

    private static String textOrNull(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? null : node.asString();
    }
}
