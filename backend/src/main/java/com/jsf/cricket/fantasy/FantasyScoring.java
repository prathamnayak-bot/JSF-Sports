package com.jsf.cricket.fantasy;

/**
 * Dream11-style T20 fantasy points for one player in one match, computed from ball-by-ball data.
 * (Simplified: no strike-rate/economy bonuses, no duck penalty, no "in announced lineup" points.)
 */
public final class FantasyScoring {

    public static final String RULES = """
            Batting: 1 per run, +1 per four, +2 per six, +8 for a half-century, +16 for a century. \
            Bowling: 25 per wicket (not run outs), +8 per bowled/LBW, +12 per maiden, +4/+8/+16 for 3/4/5 wickets. \
            Fielding: 8 per catch, 12 per stumping, 6 per run out.""";

    private FantasyScoring() {
    }

    /** What one player did in one match. */
    public record MatchLine(int runs, int fours, int sixes,
                            int wickets, int bowledOrLbw, int maidens,
                            int catches, int stumpings, int runOuts) {
    }

    public static int points(MatchLine m) {
        int pts = m.runs() + m.fours() + 2 * m.sixes();
        if (m.runs() >= 100) pts += 16;
        else if (m.runs() >= 50) pts += 8;

        pts += 25 * m.wickets() + 8 * m.bowledOrLbw() + 12 * m.maidens();
        if (m.wickets() >= 5) pts += 16;
        else if (m.wickets() == 4) pts += 8;
        else if (m.wickets() == 3) pts += 4;

        pts += 8 * m.catches() + 12 * m.stumpings() + 6 * m.runOuts();
        return pts;
    }
}
