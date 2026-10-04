package com.jsf.cricket.fantasy;

import com.jsf.cricket.domain.PlayerRole;
import com.jsf.cricket.domain.Team;
import com.jsf.cricket.domain.Venue;
import com.jsf.cricket.fantasy.FantasyRepository.PlayerMatch;
import com.jsf.cricket.fantasy.FantasyRepository.SquadPlayer;
import com.jsf.cricket.llm.LlmClient;
import com.jsf.cricket.llm.LlmException;
import com.jsf.cricket.repository.TeamRepository;
import com.jsf.cricket.repository.VenueRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Fantasy advisor: projects each squad player's fantasy points and picks an XI with captain / vice-captain.
 * <p>
 * projected = 60% recent form + 20% record at this venue + 20% record against this opponent,
 * where form is a recency-weighted average of the last {@value #FORM_MATCHES} matches and the venue /
 * opponent averages fall back to form when the player has fewer than {@value #MIN_SAMPLE} such matches.
 */
@Slf4j
@Service
public class FantasyService {

    static final int FORM_MATCHES = 10;
    static final int MIN_SAMPLE = 3;
    private static final double DECAY = 0.85;

    private static final String SUMMARY_PROMPT = """
            You are a fantasy cricket expert. In 4-6 sentences, explain the suggested fantasy XI below to a user:
            why the captain and vice-captain were chosen and which other picks stand out (strong venue or
            opponent record, all-rounders, in-form picks). Use ONLY the numbers given, write player names exactly
            as given, and don't use tables. Write naturally (e.g. "Bosch is projected for 67 points") - never
            mention JSON field names.
            """;

    private final FantasyRepository repo;
    private final TeamRepository teams;
    private final VenueRepository venues;
    private final LlmClient llm;
    private final ObjectMapper mapper;

    public FantasyService(FantasyRepository repo, TeamRepository teams, VenueRepository venues,
                          LlmClient llm, ObjectMapper mapper) {
        this.repo = repo;
        this.teams = teams;
        this.venues = venues;
        this.llm = llm;
        this.mapper = mapper;
    }

    public record Pick(long playerId, String name, long teamId, String team, PlayerRole role,
                       double projected, double form, Double venueAvg, Double opponentAvg, int matches)
            implements TeamSelector.Candidate {
    }

    public record Suggestion(String team1, String team2, String venue, String format,
                             List<Pick> xi, Long captainId, Long viceCaptainId, List<Pick> bench,
                             double projectedTotal, String summary, String scoringRules, List<String> notes) {
    }

    public Suggestion suggest(long team1Id, long team2Id, Long venueId, String format, boolean explain) {
        if (team1Id == team2Id) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pick two different teams");
        Team t1 = team(team1Id), t2 = team(team2Id);
        Venue venue = venueId == null ? null : venues.findById(venueId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Venue not found"));

        List<SquadPlayer> squad = new ArrayList<>(squad(t1, format));
        squad.addAll(squad(t2, format));
        Map<Long, List<PlayerMatch>> history = repo.matchLines(squad.stream().map(SquadPlayer::playerId).toList(), format)
                .stream().collect(Collectors.groupingBy(PlayerMatch::playerId));

        List<Pick> pool = squad.stream().map(p -> {
            Team own = p.teamId() == t1.getId() ? t1 : t2;
            Team opponent = own == t1 ? t2 : t1;
            return project(p, own, opponent, venue, history.getOrDefault(p.playerId(), List.of()));
        }).toList();

        List<Pick> xi = TeamSelector.select(pool);
        List<Pick> bench = pool.stream().filter(p -> !xi.contains(p))
                .sorted((a, b) -> Double.compare(b.projected(), a.projected())).limit(4).toList();
        Long captain = xi.isEmpty() ? null : xi.get(0).playerId();
        Long vice = xi.size() < 2 ? null : xi.get(1).playerId();
        double total = 0; // xi is sorted best-first: captain scores 2x, vice-captain 1.5x
        for (int i = 0; i < xi.size(); i++) total += xi.get(i).projected() * (i == 0 ? 2 : i == 1 ? 1.5 : 1);

        List<String> notes = new ArrayList<>();
        notes.add("Squads are each team's playing XI from their most recent " + format + " match.");
        notes.add("Roles are inferred from recent batting/bowling workload; wicket-keepers from stumpings.");
        if (xi.size() < TeamSelector.TEAM_SIZE) notes.add("Not enough eligible players for a full XI.");
        if (xi.stream().noneMatch(p -> p.role() == PlayerRole.WICKET_KEEPER)) {
            notes.add("No wicket-keeper could be identified from the data.");
        }

        Suggestion s = new Suggestion(t1.getName(), t2.getName(), venue == null ? null : venue.getName(), format,
                xi, captain, vice, bench, round(total), null, FantasyScoring.RULES, notes);
        return explain ? withSummary(s) : s;
    }

    private Pick project(SquadPlayer p, Team own, Team opponent, Venue venue, List<PlayerMatch> matches) {
        double form = weightedForm(matches);
        Double venueAvg = venue == null ? null : average(matches, m -> venue.getId().equals(m.venueId()));
        Double oppAvg = average(matches, m -> m.opponentId() == opponent.getId());
        double projected = 0.6 * form + 0.2 * (venueAvg == null ? form : venueAvg) + 0.2 * (oppAvg == null ? form : oppAvg);
        PlayerRole role = p.role() != null ? p.role() : inferRole(matches);
        return new Pick(p.playerId(), p.name(), own.getId(), own.getName(), role,
                round(projected), round(form), venueAvg == null ? null : round(venueAvg),
                oppAvg == null ? null : round(oppAvg), matches.size());
    }

    /** Recency-weighted average of the last FORM_MATCHES matches (newest weighs most). */
    static double weightedForm(List<PlayerMatch> newestFirst) {
        double sum = 0, weights = 0, w = 1;
        for (PlayerMatch m : newestFirst.subList(0, Math.min(FORM_MATCHES, newestFirst.size()))) {
            sum += w * FantasyScoring.points(m.line());
            weights += w;
            w *= DECAY;
        }
        return weights == 0 ? 0 : sum / weights;
    }

    /** Plain average over matching matches, or null when the sample is too small to trust. */
    static Double average(List<PlayerMatch> matches, Predicate<PlayerMatch> filter) {
        List<PlayerMatch> sample = matches.stream().filter(filter).toList();
        if (sample.size() < MIN_SAMPLE) return null;
        return sample.stream().mapToInt(m -> FantasyScoring.points(m.line())).average().orElse(0);
    }

    static PlayerRole inferRole(List<PlayerMatch> newestFirst) {
        if (newestFirst.stream().anyMatch(m -> m.line().stumpings() > 0)) return PlayerRole.WICKET_KEEPER;
        List<PlayerMatch> recent = newestFirst.subList(0, Math.min(15, newestFirst.size()));
        if (recent.isEmpty()) return PlayerRole.BATTER;
        double bowled = recent.stream().mapToInt(PlayerMatch::ballsBowled).average().orElse(0);
        double faced = recent.stream().mapToInt(PlayerMatch::ballsFaced).average().orElse(0);
        if (bowled >= 6) return faced >= 10 ? PlayerRole.ALL_ROUNDER : PlayerRole.BOWLER;
        return PlayerRole.BATTER;
    }

    private Suggestion withSummary(Suggestion s) {
        try {
            var brief = Map.of(
                    "match", s.team1() + " vs " + s.team2() + (s.venue() == null ? "" : " at " + s.venue()),
                    "captain", nameOf(s, s.captainId()), "vice-captain", nameOf(s, s.viceCaptainId()),
                    "xi", s.xi().stream().map(p -> {
                        var m = new LinkedHashMap<String, Object>();
                        m.put("name", p.name());
                        m.put("team", p.team());
                        m.put("role", p.role());
                        m.put("projected points", p.projected());
                        m.put("recent form (avg points)", p.form());
                        if (p.venueAvg() != null) m.put("avg points at this venue", p.venueAvg());
                        if (p.opponentAvg() != null) m.put("avg points vs this opponent", p.opponentAvg());
                        return m;
                    }).toList());
            String summary = llm.complete(SUMMARY_PROMPT, mapper.writeValueAsString(brief));
            return new Suggestion(s.team1(), s.team2(), s.venue(), s.format(), s.xi(), s.captainId(),
                    s.viceCaptainId(), s.bench(), s.projectedTotal(), summary, s.scoringRules(), s.notes());
        } catch (LlmException e) {
            log.warn("Fantasy summary skipped: {}", e.getMessage());
            List<String> notes = Stream.concat(s.notes().stream(), Stream.of("AI explanation unavailable: LLM not reachable.")).toList();
            return new Suggestion(s.team1(), s.team2(), s.venue(), s.format(), s.xi(), s.captainId(),
                    s.viceCaptainId(), s.bench(), s.projectedTotal(), null, s.scoringRules(), notes);
        }
    }

    private static String nameOf(Suggestion s, Long id) {
        return s.xi().stream().filter(p -> id != null && p.playerId() == id).map(Pick::name).findFirst().orElse("-");
    }

    private Team team(long id) {
        return teams.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Team not found"));
    }

    private List<SquadPlayer> squad(Team team, String format) {
        List<SquadPlayer> xi = repo.latestXi(team.getId(), format);
        if (xi.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, team.getName() + " has no " + format + " matches in the data");
        }
        return xi;
    }

    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
