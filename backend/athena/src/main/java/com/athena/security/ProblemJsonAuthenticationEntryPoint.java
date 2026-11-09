package com.athena.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

/**
 * Answers 401 with problem-detail JSON whose {@code code} says why, so the
 * frontend can tell an expired session (sign in again) from a missing token.
 */
public class ProblemJsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException) throws IOException {

        Object reason = request.getAttribute(JwtAuthenticationFilter.AUTH_ERROR_ATTRIBUTE);
        String code = reason instanceof String text ? text : "UNAUTHORIZED";

        String detail = switch (code) {
            case "TOKEN_EXPIRED" -> "Your session has expired. Please sign in again.";
            case "TOKEN_REVOKED" -> "This session was signed out. Please sign in again.";
            case "ACCOUNT_DISABLED" -> "This user has been disabled.";
            case "TOKEN_INVALID" -> "The access token is not valid.";
            default -> "Sign in to use this endpoint.";
        };

        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        ProblemResponses.write(response, HttpStatus.UNAUTHORIZED, code, detail);
    }
}
