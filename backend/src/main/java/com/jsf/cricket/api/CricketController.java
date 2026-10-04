package com.jsf.cricket.api;

import com.jsf.cricket.domain.CricketMatch;
import com.jsf.cricket.domain.Player;
import com.jsf.cricket.domain.Team;
import com.jsf.cricket.repository.CricketMatchRepository;
import com.jsf.cricket.repository.PlayerRepository;
import com.jsf.cricket.repository.TeamRepository;
import com.jsf.cricket.repository.VenueRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;

/** Read-only browsing endpoints used by the stats pages of the frontend. */
@RestController
@RequestMapping("/api")
public class CricketController {

    private final TeamRepository teams;
    private final PlayerRepository players;
    private final CricketMatchRepository matches;
    private final VenueRepository venues;
    private final StatsQueries stats;

    public CricketController(TeamRepository teams, PlayerRepository players, CricketMatchRepository matches,
                             VenueRepository venues, StatsQueries stats) {
        this.teams = teams;
        this.players = players;
        this.matches = matches;
        this.venues = venues;
        this.stats = stats;
    }

    public record VenueDto(Long id, String name, String city) {
    }

    public record TeamDto(Long id, String name) {
        static TeamDto of(Team t) {
            return t == null ? null : new TeamDto(t.getId(), t.getName());
        }
    }

    public record PlayerDto(Long id, String name, String role) {
        static PlayerDto of(Player p) {
            return new PlayerDto(p.getId(), p.getName(), p.getRole() == null ? null : p.getRole().name());
        }
    }

    public record MatchDto(Long id, LocalDate date, String format, String event, String venue,
                           TeamDto team1, TeamDto team2, TeamDto winner, String result) {
        static MatchDto of(CricketMatch m) {
            String result = m.getWinner() == null ? "No result / tie"
                    : m.getWinner().getName() + " won by " + m.getResultMargin() + " " + m.getResultType();
            return new MatchDto(m.getId(), m.getMatchDate(), m.getFormat(), m.getEventName(),
                    m.getVenue() == null ? null : m.getVenue().getName(),
                    TeamDto.of(m.getTeam1()), TeamDto.of(m.getTeam2()), TeamDto.of(m.getWinner()), result);
        }
    }

    public record PlayerSummary(PlayerDto player, StatsQueries.BattingSummary batting,
                                StatsQueries.BowlingSummary bowling) {
    }

    @GetMapping("/stats/overview")
    public StatsQueries.Overview overview() {
        return stats.overview();
    }

    @GetMapping("/teams")
    public List<TeamDto> teams() {
        return teams.findAll(Sort.by("name")).stream().map(TeamDto::of).toList();
    }

    @GetMapping("/teams/{id}/seasons")
    public List<StatsQueries.SeasonRecord> teamSeasons(@PathVariable long id) {
        if (!teams.existsById(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Team not found");
        return stats.teamSeasons(id);
    }

    @GetMapping("/venues")
    public List<VenueDto> venues() {
        return venues.findAll(Sort.by("name")).stream().map(v -> new VenueDto(v.getId(), v.getName(), v.getCity())).toList();
    }

    @GetMapping("/players")
    public List<PlayerDto> searchPlayers(@RequestParam(defaultValue = "") String q) {
        return players.findTop20ByNameContainingIgnoreCaseOrderByName(q).stream().map(PlayerDto::of).toList();
    }

    @GetMapping("/players/{id}/summary")
    public PlayerSummary playerSummary(@PathVariable long id) {
        Player p = players.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Player not found"));
        return new PlayerSummary(PlayerDto.of(p), stats.batting(id), stats.bowling(id));
    }

    @GetMapping("/matches")
    @Transactional(readOnly = true)
    public List<MatchDto> recentMatches(@RequestParam(defaultValue = "20") int limit) {
        return matches.findAllByOrderByMatchDateDesc(PageRequest.of(0, Math.clamp(limit, 1, 100)))
                .stream().map(MatchDto::of).toList();
    }
}
