package com.jsf.cricket.api;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Map;

/** Hand-written aggregate queries over the ball-by-ball data. */
@Repository
public class StatsQueries {

    private final JdbcTemplate jdbc;

    public StatsQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Overview(long matches, long teams, long players, long venues, long deliveries) {
    }

    public record BattingSummary(long innings, long runs, long ballsFaced, long dismissals, long fours, long sixes,
                                 Double average, Double strikeRate) {
    }

    public record BowlingSummary(long ballsBowled, long runsConceded, long wickets, Double economy, Double average) {
    }

    public Overview overview() {
        return jdbc.queryForObject("""
                SELECT (SELECT COUNT(*) FROM cricket_match), (SELECT COUNT(*) FROM team),
                       (SELECT COUNT(*) FROM player), (SELECT COUNT(*) FROM venue), (SELECT COUNT(*) FROM delivery)""",
                (rs, i) -> new Overview(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)));
    }

    public BattingSummary batting(long playerId) {
        Map<String, Object> r = jdbc.queryForMap("""
                SELECT COUNT(DISTINCT innings_id)                                   AS innings,
                       COALESCE(SUM(runs_batter), 0)                                AS runs,
                       COUNT(*) FILTER (WHERE extra_type IS NULL OR extra_type <> 'wides') AS balls,
                       COUNT(*) FILTER (WHERE runs_batter = 4)                      AS fours,
                       COUNT(*) FILTER (WHERE runs_batter = 6)                      AS sixes
                FROM delivery WHERE batter_id = ?""", playerId);
        long dismissals = count("""
                SELECT COUNT(*) FROM delivery
                WHERE player_out_id = ? AND wicket_kind NOT IN ('retired hurt', 'retired not out')""", playerId);
        long runs = num(r.get("runs")), balls = num(r.get("balls"));
        return new BattingSummary(num(r.get("innings")), runs, balls, dismissals, num(r.get("fours")),
                num(r.get("sixes")), ratio(runs, dismissals, 1), ratio(runs, balls, 100));
    }

    public BowlingSummary bowling(long playerId) {
        Map<String, Object> r = jdbc.queryForMap("""
                SELECT COUNT(*) FILTER (WHERE is_legal_ball)                        AS balls,
                       COALESCE(SUM(runs_total) FILTER (WHERE extra_type IS NULL
                                OR extra_type IN ('wides', 'noballs')), 0)          AS runs,
                       COUNT(*) FILTER (WHERE wicket_kind IS NOT NULL AND wicket_kind NOT IN
                                ('run out', 'retired hurt', 'retired out', 'retired not out',
                                 'obstructing the field'))                          AS wickets
                FROM delivery WHERE bowler_id = ?""", playerId);
        long balls = num(r.get("balls")), runs = num(r.get("runs")), wickets = num(r.get("wickets"));
        return new BowlingSummary(balls, runs, wickets, ratio(runs, balls, 6), ratio(runs, wickets, 1));
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    private static long num(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }

    private static Double ratio(long numerator, long denominator, double scale) {
        return denominator == 0 ? null : Math.round(scale * numerator / denominator * 100) / 100.0;
    }
}
