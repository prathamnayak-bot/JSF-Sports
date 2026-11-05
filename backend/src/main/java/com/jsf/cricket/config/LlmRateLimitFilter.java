package com.jsf.cricket.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

/**
 * Limits requests that call the LLM (AI chat, and fantasy suggestions with explain=true), so a public
 * deployment cannot use up the free LLM quota. Two fixed windows, kept in memory:
 * per client per minute, and for the whole server per day. 0 switches a limit off.
 */
@Component
public class LlmRateLimitFilter extends OncePerRequestFilter {

    private final int perClientPerMinute;
    private final int perDay;
    private final Clock clock;

    private final Map<String, Integer> clientCounts = new HashMap<>();
    private long currentMinute = -1;
    private long currentDay = -1;
    private int dayCount;

    @Autowired
    public LlmRateLimitFilter(@Value("${app.rate-limit.llm.per-client-per-minute:10}") int perClientPerMinute,
                              @Value("${app.rate-limit.llm.per-day:300}") int perDay) {
        this(perClientPerMinute, perDay, Clock.systemUTC());
    }

    LlmRateLimitFilter(int perClientPerMinute, int perDay, Clock clock) {
        this.perClientPerMinute = perClientPerMinute;
        this.perDay = perDay;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        boolean chat = "POST".equals(request.getMethod()) && path.equals("/api/chat");
        boolean explainedFantasy = path.equals("/api/fantasy/suggest") && "true".equals(request.getParameter("explain"));
        return !(chat || explainedFantasy);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String refusal = tryAcquire(clientKey(request));
        if (refusal == null) {
            chain.doFilter(request, response);
        } else {
            response.setHeader("Retry-After", "60");
            ProblemResponses.write(response, HttpStatus.TOO_MANY_REQUESTS, refusal);
        }
    }

    /** Counts one request; returns null if allowed, otherwise a message for the user. */
    synchronized String tryAcquire(String client) {
        long now = clock.millis();
        long minute = now / 60_000;
        long day = now / 86_400_000;
        if (minute != currentMinute) {
            currentMinute = minute;
            clientCounts.clear();
        }
        if (day != currentDay) {
            currentDay = day;
            dayCount = 0;
        }
        if (perDay > 0 && dayCount >= perDay) {
            return "The AI features have reached today's limit on this server. Please try again tomorrow.";
        }
        int mine = clientCounts.getOrDefault(client, 0);
        if (perClientPerMinute > 0 && mine >= perClientPerMinute) {
            return "Too many AI questions in a minute. Please wait a moment and try again.";
        }
        clientCounts.put(client, mine + 1);
        dayCount++;
        return null;
    }

    /**
     * The browser's address. Behind a hosting proxy (Render, nginx) that is the last X-Forwarded-For entry
     * added by the proxy; the first entries can be set by the client itself, so they are not trusted.
     */
    private static String clientKey(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String[] hops = forwarded.split(",");
            return hops[hops.length - 1].trim();
        }
        return request.getRemoteAddr();
    }
}
