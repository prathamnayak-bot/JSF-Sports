package com.jsf.cricket.fixtures;

import com.jsf.cricket.ingest.CricsheetImporter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.hamcrest.Matchers.startsWith;

@SpringBootTest
@ActiveProfiles({"h2", "test"})
class FixtureServiceTest {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    CricsheetImporter importer;

    // trimmed copies of real CricketData.org responses
    private static final String SERIES = """
            {"status": "success", "data": [
              {"id": "s2027", "name": "Indian Premier League 2027", "matches": 0},
              {"id": "s2026", "name": "Indian Premier League 2026", "matches": 74},
              {"id": "s2025", "name": "Indian Premier League 2025 (IPL)", "matches": 75}]}""";
    private static final String SERIES_INFO = """
            {"status": "success", "data": {"info": {"name": "Indian Premier League 2026"}, "matchList": [
              {"id": "m1", "name": "Chennai Super Kings vs Royal Challengers Bengaluru, 1st Match",
               "status": "Match starts at Mar 28, 14:00 GMT", "venue": "MA Chidambaram Stadium, Chennai",
               "dateTimeGMT": "2027-03-28T14:00:00", "teams": ["Chennai Super Kings", "Royal Challengers Bengaluru"],
               "matchStarted": false, "matchEnded": false},
              {"id": "m0", "name": "Gujarat Titans vs Punjab Kings, 2nd Match", "status": "Punjab Kings won by 4 wkts",
               "venue": "Narendra Modi Stadium, Ahmedabad", "dateTimeGMT": "2026-03-29T14:00:00",
               "teams": ["Gujarat Titans", "Punjab Kings"], "matchStarted": true, "matchEnded": true}]}}""";

    @Test
    void loadsNewestSeriesWithFixturesAndLinksKnownTeamsAndVenues() throws Exception {
        importer.importDirectory(new ClassPathResource("cricsheet").getFile().toPath()); // CSK, RCB, Chepauk

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer api = MockRestServiceServer.bindTo(builder).build();
        api.expect(requestTo(startsWith("https://api.test/series?")))
                .andExpect(queryParam("search", "Indian%20Premier%20League"))
                .andRespond(withSuccess(SERIES, MediaType.APPLICATION_JSON));
        api.expect(requestTo(startsWith("https://api.test/series_info?")))
                .andExpect(queryParam("id", "s2026")) // 2027 has no fixtures yet
                .andRespond(withSuccess(SERIES_INFO, MediaType.APPLICATION_JSON));

        FixtureService service = new FixtureService(jdbc, tx, builder, "https://api.test", "test-key",
                "Indian Premier League");
        assertThat(service.refresh()).isEqualTo(new FixtureService.RefreshResult("Indian Premier League 2026", 2));
        api.verify();

        List<FixtureService.Fixture> upcoming = service.list(true, 10);
        assertThat(upcoming).singleElement().satisfies(f -> {
            assertThat(f.team1()).isEqualTo("Chennai Super Kings");
            assertThat(f.startTimeGmt()).isEqualTo(LocalDateTime.of(2027, 3, 28, 14, 0));
            assertThat(f.team1Id()).as("linked to our team").isNotNull();
            assertThat(f.team2Id()).isNotNull();
            assertThat(f.venueId()).as("'MA Chidambaram Stadium, Chennai' matches the normalised venue").isNotNull();
        });
        assertThat(service.list(false, 10)).singleElement()
                .satisfies(f -> assertThat(f.team1Id()).as("Gujarat Titans not in the sample data").isNull());
    }
}
