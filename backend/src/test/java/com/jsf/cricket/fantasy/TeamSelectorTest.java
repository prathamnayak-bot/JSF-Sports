package com.jsf.cricket.fantasy;

import com.jsf.cricket.domain.PlayerRole;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.jsf.cricket.domain.PlayerRole.*;
import static org.assertj.core.api.Assertions.assertThat;

class TeamSelectorTest {

    record P(String name, long teamId, PlayerRole role, double projected) implements TeamSelector.Candidate {
    }

    @Test
    void picksElevenRespectingRoleAndTeamLimits() {
        List<P> pool = new ArrayList<>();
        // team 1 is much stronger, and full of batters: limits must still apply
        for (int i = 0; i < 8; i++) pool.add(new P("t1-bat" + i, 1, BATTER, 90 - i));
        pool.add(new P("t1-wk", 1, WICKET_KEEPER, 40));
        pool.add(new P("t1-bowl", 1, BOWLER, 50));
        pool.add(new P("t1-ar", 1, ALL_ROUNDER, 60));
        pool.add(new P("t2-wk", 2, WICKET_KEEPER, 20));
        for (int i = 0; i < 6; i++) pool.add(new P("t2-bowl" + i, 2, BOWLER, 30 - i));
        for (int i = 0; i < 4; i++) pool.add(new P("t2-bat" + i, 2, BATTER, 10 - i));

        List<P> xi = TeamSelector.select(pool);

        assertThat(xi).hasSize(11);
        Map<PlayerRole, Long> roles = xi.stream().collect(Collectors.groupingBy(P::role, Collectors.counting()));
        assertThat(roles.get(WICKET_KEEPER)).isBetween(1L, 4L);
        assertThat(roles.get(BATTER)).isBetween(3L, 6L);
        assertThat(roles.get(ALL_ROUNDER)).isBetween(1L, 4L);
        assertThat(roles.get(BOWLER)).isBetween(3L, 6L);
        assertThat(xi.stream().filter(p -> p.teamId() == 1).count()).isLessThanOrEqualTo(TeamSelector.MAX_FROM_ONE_TEAM);
        assertThat(xi.getFirst().name()).as("sorted best first (captain)").isEqualTo("t1-bat0");
    }

    @Test
    void returnsWhatItCanWhenThePoolIsSmall() {
        List<P> pool = List.of(new P("a", 1, BATTER, 10), new P("b", 2, BOWLER, 5));
        assertThat(TeamSelector.select(pool)).hasSize(2);
    }
}
