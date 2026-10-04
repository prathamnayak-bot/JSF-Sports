package com.jsf.cricket.fantasy;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

@RestController
@RequestMapping("/api/fantasy")
public class FantasyController {

    private final FantasyService fantasy;

    public FantasyController(FantasyService fantasy) {
        this.fantasy = fantasy;
    }

    /** e.g. /api/fantasy/suggest?team1=3&team2=7&venue=12&explain=true */
    @GetMapping("/suggest")
    public FantasyService.Suggestion suggest(@RequestParam long team1, @RequestParam long team2,
                                             @RequestParam(required = false) Long venue,
                                             @RequestParam(defaultValue = "T20") String format,
                                             @RequestParam(defaultValue = "false") boolean explain) {
        return fantasy.suggest(team1, team2, venue, format.toUpperCase(Locale.ROOT), explain);
    }
}
