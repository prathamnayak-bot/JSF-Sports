package com.jsf.cricket.fixtures;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@Tag(name = "Fixtures", description = "Upcoming and recent matches from CricketData.org")
public class FixtureController {

    private final FixtureService fixtures;

    public FixtureController(FixtureService fixtures) {
        this.fixtures = fixtures;
    }

    public record FixtureList(boolean enabled, boolean upcoming, List<FixtureService.Fixture> fixtures) {
    }

    /** Upcoming fixtures; if none are scheduled yet, the most recent completed ones (upcoming=false). */
    @GetMapping("/api/fixtures")
    public FixtureList list(@RequestParam(defaultValue = "20") int limit) {
        int n = Math.clamp(limit, 1, 100);
        List<FixtureService.Fixture> upcoming = fixtures.list(true, n);
        return upcoming.isEmpty()
                ? new FixtureList(fixtures.enabled(), false, fixtures.list(false, n))
                : new FixtureList(fixtures.enabled(), true, upcoming);
    }

    @PostMapping("/api/admin/fixtures/refresh")
    public FixtureService.RefreshResult refresh() {
        return fixtures.refresh();
    }
}
