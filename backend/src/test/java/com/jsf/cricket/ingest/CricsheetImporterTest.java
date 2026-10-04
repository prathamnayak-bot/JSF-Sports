package com.jsf.cricket.ingest;

import com.jsf.cricket.api.StatsQueries;
import com.jsf.cricket.repository.PlayerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("h2")
class CricsheetImporterTest {

    @Autowired
    CricsheetImporter importer;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PlayerRepository players;
    @Autowired
    StatsQueries stats;

    @Test
    void importsSampleMatchAndComputesStats() throws Exception {
        Path dir = new ClassPathResource("cricsheet").getFile().toPath();

        CricsheetImporter.ImportResult first = importer.importDirectory(dir);
        assertThat(first.imported()).isEqualTo(1);
        assertThat(first.failed()).isZero();
        assertThat(importer.importDirectory(dir).skipped()).as("re-import is skipped").isEqualTo(1);

        Map<String, Object> match = jdbc.queryForMap("""
                SELECT m.format, m.toss_decision, m.result_margin, m.result_type, w.name AS winner, v.city
                FROM cricket_match m JOIN team w ON w.id = m.winner_team_id JOIN venue v ON v.id = m.venue_id""");
        assertThat(match).containsEntry("format", "T20").containsEntry("winner", "Chennai Super Kings")
                .containsEntry("result_margin", 9).containsEntry("result_type", "wickets")
                .containsEntry("city", "Chennai");

        // super over is skipped, so only 2 innings
        assertThat(jdbc.queryForList("SELECT total_runs, total_wickets, legal_balls FROM innings ORDER BY innings_number"))
                .containsExactly(
                        Map.of("total_runs", 13, "total_wickets", 1, "legal_balls", 6),
                        Map.of("total_runs", 13, "total_wickets", 0, "legal_balls", 2));

        assertThat(players.findByCricsheetId("uuuu0001")).as("umpires are not players").isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM match_player", Integer.class)).isEqualTo(6);

        long batterA = players.findByCricsheetId("aaaa0001").orElseThrow().getId();
        StatsQueries.BattingSummary bat = stats.batting(batterA);
        assertThat(bat.runs()).isEqualTo(4);
        assertThat(bat.ballsFaced()).as("wide is not a ball faced").isEqualTo(2);
        assertThat(bat.dismissals()).isEqualTo(1);
        assertThat(bat.strikeRate()).isEqualTo(200.0);

        long bowlerOne = players.findByCricsheetId("bbbb0003").orElseThrow().getId();
        StatsQueries.BowlingSummary bowl = stats.bowling(bowlerOne);
        assertThat(bowl.ballsBowled()).isEqualTo(6);
        assertThat(bowl.runsConceded()).as("leg-bye not charged to bowler").isEqualTo(12);
        assertThat(bowl.wickets()).isEqualTo(1);
        assertThat(bowl.economy()).isEqualTo(12.0);
    }
}
