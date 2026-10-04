package com.jsf.cricket.fantasy;

import com.jsf.cricket.fantasy.FantasyScoring.MatchLine;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FantasyScoringTest {

    @Test
    void battingPointsWithBoundaryAndMilestoneBonuses() {
        // 54 runs + 5 fours + 2*3 sixes + 8 half-century bonus
        assertThat(FantasyScoring.points(new MatchLine(54, 5, 3, 0, 0, 0, 0, 0, 0))).isEqualTo(54 + 5 + 6 + 8);
        // century bonus replaces the half-century bonus
        assertThat(FantasyScoring.points(new MatchLine(100, 0, 0, 0, 0, 0, 0, 0, 0))).isEqualTo(116);
    }

    @Test
    void bowlingPointsWithHaulBonuses() {
        // 3 wickets (1 bowled) + 1 maiden: 75 + 8 + 12 + 4
        assertThat(FantasyScoring.points(new MatchLine(0, 0, 0, 3, 1, 1, 0, 0, 0))).isEqualTo(99);
        assertThat(FantasyScoring.points(new MatchLine(0, 0, 0, 5, 0, 0, 0, 0, 0))).isEqualTo(125 + 16);
    }

    @Test
    void fieldingPoints() {
        assertThat(FantasyScoring.points(new MatchLine(0, 0, 0, 0, 0, 0, 2, 1, 1))).isEqualTo(16 + 12 + 6);
    }
}
