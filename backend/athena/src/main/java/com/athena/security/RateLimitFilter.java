package com.athena.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Answers 429 when one client IP sends more API requests per minute than
 * allowed. It runs before authentication, so a flood is turned away before
 * any token is parsed or database query made. The health check is exempt.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimitService rateLimitService;

    public RateLimitFilter(RateLimitService rateLimitService) {
        this.rateLimitService = rateLimitService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/") || path.equals("/api/health");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        long wait = rateLimitService.tryApiRequest(request.getRemoteAddr());
        if (wait > 0) {
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(wait));
            ProblemResponses.write(response, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                    "Too many requests. Slow down and try again shortly.");
            return;
        }

        filterChain.doFilter(request, response);
    }
}
