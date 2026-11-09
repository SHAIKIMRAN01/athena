package com.athena.security;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;

import java.io.IOException;

/**
 * Writes RFC 9457 problem-detail JSON from servlet filters, which run before
 * Spring MVC and its exception handler. The JSON matches the shape of
 * GlobalExceptionHandler's responses, so the frontend handles both alike.
 */
final class ProblemResponses {

    static final String PROBLEM_JSON = "application/problem+json";

    private ProblemResponses() {}

    static void write(HttpServletResponse response, HttpStatus status, String code, String detail)
            throws IOException {

        response.setStatus(status.value());
        response.setContentType(PROBLEM_JSON);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + escape(status.getReasonPhrase())
                + "\",\"status\":" + status.value()
                + ",\"detail\":\"" + escape(detail)
                + "\",\"code\":\"" + escape(code) + "\"}");
    }

    private static String escape(String text) {
        StringBuilder escaped = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            if (c == '"') {
                escaped.append("\\\"");
            } else if (c == '\\') {
                escaped.append("\\\\");
            } else if (c < 0x20) {
                escaped.append(String.format("\\u%04x", (int) c));
            } else {
                escaped.append(c);
            }
        }
        return escaped.toString();
    }
}
