package com.jsf.cricket.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Protects /api/admin/** (data import, sync, fixture refresh) on a public deployment.
 * When ADMIN_TOKEN is set, those requests must send the header {@code X-Admin-Token: <token>}.
 * When it is not set (local development) the endpoints stay open, as before.
 */
@Slf4j
@Component
public class AdminTokenFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Admin-Token";

    private final byte[] token;

    public AdminTokenFilter(@Value("${app.admin.token:}") String token) {
        this.token = StringUtils.hasText(token) ? token.getBytes(StandardCharsets.UTF_8) : null;
        if (this.token == null) {
            log.warn("ADMIN_TOKEN is not set: /api/admin/** is open to anyone (fine locally, not on a public server)");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return token == null || !request.getRequestURI().startsWith("/api/admin/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String sent = request.getHeader(HEADER);
        // constant-time comparison, so the token cannot be guessed from response timings
        if (sent != null && MessageDigest.isEqual(token, sent.getBytes(StandardCharsets.UTF_8))) {
            chain.doFilter(request, response);
        } else {
            ProblemResponses.write(response, HttpStatus.UNAUTHORIZED,
                    "Admin endpoints need the " + HEADER + " header.");
        }
    }
}
