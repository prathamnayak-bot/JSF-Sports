package com.jsf.cricket.fantasy;

import com.jsf.cricket.api.StatsQueries;
import com.jsf.cricket.domain.PlayerRole;
import com.jsf.cricket.ingest.CricsheetImporter;
import com.jsf.cricket.repository.TeamRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles({"h2", "test"})
class FantasyServiceTest {

    @Autowired
    CricsheetImporter importer;
    @Autowired
    FantasyService fantasy;
    @Autowired
    TeamRepository teams;
    @Autowired
    StatsQueries stats;

    @Test
    void suggestsTeamFromSampleMatch() throws Exception {
        importer.importDirectory(new ClassPathResource("cricsheet").getFile().toPath());
        long rcb = teams.findByName("Royal Challengers Bengaluru").orElseThrow().getId();
        long csk = teams.findByName("Chennai Super Kings").orElseThrow().getId();

        FantasyService.Suggestion s = fantasy.suggest(rcb, csk, null, "T20", false);

        // only 3 players per side in the sample, so all 6 are picked
        assertThat(s.xi()).hasSize(6);
        // Bowler One: 1 wicket = 25 points (the over cost runs, so no maiden)
        // Batter C: 12 runs + 1 four (1) + 1 six (2) + a catch (8) = 23 points
        FantasyService.Pick captain = s.xi().getFirst();
        assertThat(captain.name()).isEqualTo("Bowler One");
        assertThat(captain.projected()).isEqualTo(25.0);
        assertThat(captain.role()).isEqualTo(PlayerRole.BOWLER);
        assertThat(s.captainId()).isEqualTo(captain.playerId());

        FantasyService.Pick batterC = s.xi().stream().filter(p -> p.name().equals("Batter C")).findFirst().orElseThrow();
        assertThat(batterC.projected()).as("runs + boundary bonuses + catch").isEqualTo(23.0);
        assertThat(s.notes()).anyMatch(n -> n.contains("Not enough eligible players"));

        var form = fantasy.playerForm(batterC.playerId(), "T20", 20);
        assertThat(form).singleElement().satisfies(f -> {
            assertThat(f.points()).isEqualTo(23);
            assertThat(f.runs()).isEqualTo(12);
            assertThat(f.opponent()).isEqualTo("Royal Challengers Bengaluru");
        });

        assertThat(stats.teamSeasons(csk)).singleElement()
                .isEqualTo(new StatsQueries.SeasonRecord("2024", 2024, 1, 1, 0));
    }
}
