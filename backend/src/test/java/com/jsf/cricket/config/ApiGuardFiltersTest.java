package com.jsf.cricket.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class ApiGuardFiltersTest {

    // --- admin token ---

    private static MockHttpServletResponse admin(AdminTokenFilter filter, String path, String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        if (token != null) request.addHeader(AdminTokenFilter.HEADER, token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void adminEndpointsNeedTheTokenWhenOneIsSet() throws Exception {
        AdminTokenFilter filter = new AdminTokenFilter("s3cret");
        assertThat(admin(filter, "/api/admin/sync", null).getStatus()).isEqualTo(401);
        assertThat(admin(filter, "/api/admin/sync", "wrong").getStatus()).isEqualTo(401);
        assertThat(admin(filter, "/api/admin/sync", "s3cret").getStatus()).isEqualTo(200);
        assertThat(admin(filter, "/api/chat", null).getStatus()).isEqualTo(200);
    }

    @Test
    void adminEndpointsStayOpenWithoutAToken() throws Exception {
        assertThat(admin(new AdminTokenFilter(""), "/api/admin/sync", null).getStatus()).isEqualTo(200);
    }

    @Test
    void refusalIsAProblemJsonBody() throws Exception {
        MockHttpServletResponse response = admin(new AdminTokenFilter("s3cret"), "/api/admin/sync", null);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getContentAsString()).contains("\"status\":401", "X-Admin-Token");
    }

    // --- LLM rate limit ---

    private final AtomicLong now = new AtomicLong(Instant.parse("2026-11-05T10:00:00Z").toEpochMilli());
    private final Clock clock = new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now.get()); }
    };

    @Test
    void limitsEachClientPerMinute() {
        LlmRateLimitFilter limiter = new LlmRateLimitFilter(2, 0, clock);
        assertThat(limiter.tryAcquire("a")).isNull();
        assertThat(limiter.tryAcquire("a")).isNull();
        assertThat(limiter.tryAcquire("a")).contains("minute");
        assertThat(limiter.tryAcquire("b")).isNull();   // other clients are not affected
        now.addAndGet(60_000);
        assertThat(limiter.tryAcquire("a")).isNull();   // new minute, new allowance
    }

    @Test
    void limitsTheWholeServerPerDay() {
        LlmRateLimitFilter limiter = new LlmRateLimitFilter(0, 3, clock);
        assertThat(limiter.tryAcquire("a")).isNull();
        assertThat(limiter.tryAcquire("b")).isNull();
        assertThat(limiter.tryAcquire("c")).isNull();
        assertThat(limiter.tryAcquire("d")).contains("today");
        now.addAndGet(86_400_000);
        assertThat(limiter.tryAcquire("d")).isNull();
    }

    @Test
    void onlyLlmRequestsAreCounted() throws Exception {
        LlmRateLimitFilter limiter = new LlmRateLimitFilter(1, 0, clock);
        assertThat(run(limiter, "GET", "/api/stats/overview", null)).isEqualTo(200);
        assertThat(run(limiter, "GET", "/api/fantasy/suggest", "false")).isEqualTo(200);
        assertThat(run(limiter, "POST", "/api/chat", null)).isEqualTo(200);
        assertThat(run(limiter, "POST", "/api/chat", null)).isEqualTo(429);
        assertThat(run(limiter, "GET", "/api/fantasy/suggest", "true")).isEqualTo(429);
        assertThat(run(limiter, "GET", "/api/fantasy/suggest", "false")).isEqualTo(200);
    }

    private static int run(LlmRateLimitFilter filter, String method, String path, String explain) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        if (explain != null) request.setParameter("explain", explain);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }
}
