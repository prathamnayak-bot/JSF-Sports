package com.jsf.cricket.fantasy;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api")
public class FantasyController {

    private final FantasyService fantasy;

    public FantasyController(FantasyService fantasy) {
        this.fantasy = fantasy;
    }

    /** e.g. /api/fantasy/suggest?team1=3&team2=7&venue=12&explain=true */
    @GetMapping("/fantasy/suggest")
    public FantasyService.Suggestion suggest(@RequestParam long team1, @RequestParam long team2,
                                             @RequestParam(required = false) Long venue,
                                             @RequestParam(defaultValue = "T20") String format,
                                             @RequestParam(defaultValue = "false") boolean explain) {
        return fantasy.suggest(team1, team2, venue, format.toUpperCase(Locale.ROOT), explain);
    }

    /** Fantasy points per match for the form chart, e.g. /api/players/42/form?limit=20 */
    @GetMapping("/players/{id}/form")
    public List<FantasyService.FormPoint> form(@PathVariable long id,
                                               @RequestParam(defaultValue = "T20") String format,
                                               @RequestParam(defaultValue = "20") int limit) {
        return fantasy.playerForm(id, format.toUpperCase(Locale.ROOT), Math.clamp(limit, 1, 100));
    }
}
