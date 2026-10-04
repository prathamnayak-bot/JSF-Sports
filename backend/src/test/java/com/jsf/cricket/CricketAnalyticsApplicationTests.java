package com.jsf.cricket;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles({"h2", "test"})
class CricketAnalyticsApplicationTests {

    @Test
    void contextLoads() {
    }
}
