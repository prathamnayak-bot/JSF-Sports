package com.jsf.cricket.fantasy;

import com.jsf.cricket.domain.PlayerRole;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Aggregates ball-by-ball data into per-player, per-match fantasy lines. */
@Repository
public class FantasyRepository {

    private static final String NOT_BOWLER_WICKETS =
            "('run out', 'retired hurt', 'retired out', 'retired not out', 'obstructing the field')";

    private final NamedParameterJdbcTemplate jdbc;

    public FantasyRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record SquadPlayer(long playerId, String name, long teamId, PlayerRole role) {
    }

    /** One player's contribution in one match, plus context for venue / opponent factors. */
    public record PlayerMatch(long playerId, long matchId, LocalDate date, Long venueId, long opponentId,
                              FantasyScoring.MatchLine line, int ballsFaced, int ballsBowled) {
    }

    /** The playing XI from the team's most recent match in this format (our best guess at the current squad). */
    public List<SquadPlayer> latestXi(long teamId, String format) {
        return jdbc.query("""
                SELECT p.id, p.name, mp.team_id, p.role
                FROM match_player mp JOIN player p ON p.id = mp.player_id
                WHERE mp.team_id = :team AND mp.match_id = (
                    SELECT m.id FROM cricket_match m
                    WHERE (m.team1_id = :team OR m.team2_id = :team) AND m.format = :format
                    ORDER BY m.match_date DESC, m.id DESC LIMIT 1)""",
                new MapSqlParameterSource("team", teamId).addValue("format", format),
                (rs, i) -> new SquadPlayer(rs.getLong(1), rs.getString(2), rs.getLong(3),
                        rs.getString(4) == null ? null : PlayerRole.valueOf(rs.getString(4))));
    }

    /**
     * Every match (in this format) each given player appeared in, newest first per player.
     * Runs five simple grouped queries and merges them in Java - a single query with joined CTEs
     * is very slow on H2, which re-evaluates the CTEs for every row.
     */
    public List<PlayerMatch> matchLines(Collection<Long> playerIds, String format) {
        MapSqlParameterSource params = new MapSqlParameterSource("ids", playerIds).addValue("format", format);

        Map<String, int[]> bat = stats("""
                SELECT i.match_id, d.batter_id, SUM(d.runs_batter),
                       COUNT(CASE WHEN d.runs_batter = 4 THEN 1 END), COUNT(CASE WHEN d.runs_batter = 6 THEN 1 END),
                       COUNT(CASE WHEN d.extra_type IS NULL OR d.extra_type <> 'wides' THEN 1 END)
                FROM delivery d JOIN innings i ON i.id = d.innings_id
                WHERE d.batter_id IN (:ids)
                GROUP BY i.match_id, d.batter_id""", params, 4);
        Map<String, int[]> bowl = stats("""
                SELECT i.match_id, d.bowler_id,
                       COUNT(CASE WHEN d.wicket_kind IS NOT NULL AND d.wicket_kind NOT IN %s THEN 1 END),
                       COUNT(CASE WHEN d.wicket_kind IN ('bowled', 'lbw') THEN 1 END),
                       COUNT(CASE WHEN d.is_legal_ball THEN 1 END)
                FROM delivery d JOIN innings i ON i.id = d.innings_id
                WHERE d.bowler_id IN (:ids)
                GROUP BY i.match_id, d.bowler_id""".formatted(NOT_BOWLER_WICKETS), params, 3);
        Map<String, int[]> maidens = stats("""
                SELECT i.match_id, o.bowler_id, COUNT(*)
                FROM (SELECT d.innings_id, d.bowler_id
                      FROM delivery d
                      WHERE d.bowler_id IN (:ids)
                      GROUP BY d.innings_id, d.over_number, d.bowler_id
                      HAVING COUNT(CASE WHEN d.is_legal_ball THEN 1 END) = 6
                         AND SUM(CASE WHEN d.extra_type IS NULL OR d.extra_type IN ('wides', 'noballs')
                                      THEN d.runs_total ELSE 0 END) = 0) o
                JOIN innings i ON i.id = o.innings_id
                GROUP BY i.match_id, o.bowler_id""", params, 1);
        Map<String, int[]> field = stats("""
                SELECT i.match_id, d.fielder_id,
                       COUNT(CASE WHEN d.wicket_kind IN ('caught', 'caught and bowled') THEN 1 END),
                       COUNT(CASE WHEN d.wicket_kind = 'stumped' THEN 1 END),
                       COUNT(CASE WHEN d.wicket_kind = 'run out' THEN 1 END)
                FROM delivery d JOIN innings i ON i.id = d.innings_id
                WHERE d.fielder_id IN (:ids)
                GROUP BY i.match_id, d.fielder_id""", params, 3);

        int[] none4 = new int[4], none3 = new int[3], none1 = new int[1];
        return jdbc.query("""
                SELECT mp.player_id, mp.match_id, m.match_date, m.venue_id,
                       CASE WHEN m.team1_id = mp.team_id THEN m.team2_id ELSE m.team1_id END AS opponent_id
                FROM match_player mp JOIN cricket_match m ON m.id = mp.match_id
                WHERE mp.player_id IN (:ids) AND m.format = :format
                ORDER BY mp.player_id, m.match_date DESC, m.id DESC""", params, (rs, i) -> {
            long playerId = rs.getLong("player_id"), matchId = rs.getLong("match_id");
            String key = matchId + ":" + playerId;
            int[] b = bat.getOrDefault(key, none4), w = bowl.getOrDefault(key, none3);
            int[] mo = maidens.getOrDefault(key, none1), f = field.getOrDefault(key, none3);
            return new PlayerMatch(playerId, matchId, rs.getObject("match_date", LocalDate.class),
                    rs.getObject("venue_id", Long.class), rs.getLong("opponent_id"),
                    new FantasyScoring.MatchLine(b[0], b[1], b[2], w[0], w[1], mo[0], f[0], f[1], f[2]),
                    b[3], w[2]);
        });
    }

    /** Runs a "match_id, player_id, n1..nk" query into a map keyed by "matchId:playerId". */
    private Map<String, int[]> stats(String sql, MapSqlParameterSource params, int columns) {
        Map<String, int[]> out = new HashMap<>();
        jdbc.query(sql, params, rs -> {
            int[] values = new int[columns];
            for (int c = 0; c < columns; c++) values[c] = rs.getInt(c + 3);
            out.put(rs.getLong(1) + ":" + rs.getLong(2), values);
        });
        return out;
    }
}
