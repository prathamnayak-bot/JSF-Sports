package com.jsf.cricket.fantasy;

import com.jsf.cricket.domain.PlayerRole;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Picks a fantasy XI from two squads under Dream11-style rules:
 * 11 players, at most {@value #MAX_FROM_ONE_TEAM} from one team, and per role 1-4 WK, 3-6 BAT, 1-4 AR, 3-6 BOWL.
 * Greedy: first meet each role's minimum with its best players, then fill by projected points.
 */
public final class TeamSelector {

    public static final int TEAM_SIZE = 11;
    public static final int MAX_FROM_ONE_TEAM = 7;

    private static final Map<PlayerRole, int[]> ROLE_LIMITS = new EnumMap<>(Map.of(
            PlayerRole.WICKET_KEEPER, new int[]{1, 4},
            PlayerRole.BATTER, new int[]{3, 6},
            PlayerRole.ALL_ROUNDER, new int[]{1, 4},
            PlayerRole.BOWLER, new int[]{3, 6}));

    private TeamSelector() {
    }

    /** A selectable player; {@code teamId} is used for the per-team cap. */
    public interface Candidate {
        long teamId();

        PlayerRole role();

        double projected();
    }

    public static <C extends Candidate> List<C> select(List<C> pool) {
        List<C> ranked = new ArrayList<>(pool);
        ranked.sort(Comparator.comparingDouble(Candidate::projected).reversed());

        List<C> picked = new ArrayList<>();
        Map<PlayerRole, Integer> perRole = new EnumMap<>(PlayerRole.class);
        Map<Long, Integer> perTeam = new HashMap<>();

        // 1) role minimums, best players first
        for (PlayerRole role : ROLE_LIMITS.keySet()) {
            for (C c : ranked) {
                if (perRole.getOrDefault(role, 0) >= ROLE_LIMITS.get(role)[0]) break;
                if (c.role() == role && !picked.contains(c) && fits(c, perRole, perTeam)) {
                    add(c, picked, perRole, perTeam);
                }
            }
        }
        // 2) fill the rest by projected points
        for (C c : ranked) {
            if (picked.size() >= TEAM_SIZE) break;
            if (!picked.contains(c) && fits(c, perRole, perTeam)) add(c, picked, perRole, perTeam);
        }
        picked.sort(Comparator.comparingDouble(Candidate::projected).reversed());
        return picked;
    }

    private static boolean fits(Candidate c, Map<PlayerRole, Integer> perRole, Map<Long, Integer> perTeam) {
        return perRole.getOrDefault(c.role(), 0) < ROLE_LIMITS.get(c.role())[1]
                && perTeam.getOrDefault(c.teamId(), 0) < MAX_FROM_ONE_TEAM;
    }

    private static <C extends Candidate> void add(C c, List<C> picked,
                                                  Map<PlayerRole, Integer> perRole, Map<Long, Integer> perTeam) {
        picked.add(c);
        perRole.merge(c.role(), 1, Integer::sum);
        perTeam.merge(c.teamId(), 1, Integer::sum);
    }
}
