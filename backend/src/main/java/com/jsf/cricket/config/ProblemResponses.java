package com.jsf.cricket.config;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.io.IOException;

/** RFC 7807 error bodies for servlet filters, which run before Spring MVC's exception handlers. */
final class ProblemResponses {

    private ProblemResponses() {
    }

    static void write(HttpServletResponse response, HttpStatus status, String detail) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        String escaped = detail.replace("\\", "\\\\").replace("\"", "\\\"");
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + status.getReasonPhrase()
                + "\",\"status\":" + status.value() + ",\"detail\":\"" + escaped + "\"}");
    }
}
