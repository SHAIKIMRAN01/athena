package com.athena.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Authenticates requests that carry {@code Authorization: Bearer <token>}.
 *
 * <p>A token is accepted only if its signature, issuer and expiry are valid,
 * it has not been revoked by a logout, and its user still exists and is
 * enabled. The user and their roles are loaded from the database on every
 * request, so disabling a user or changing their roles takes effect at once.
 *
 * <p>A rejected token leaves the request unauthenticated and records why in
 * a request attribute; {@link ProblemJsonAuthenticationEntryPoint} turns that
 * into the 401 response's {@code code} (TOKEN_EXPIRED, TOKEN_REVOKED, ...).
 * Tokens are never logged.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** Why the request's token was rejected, for the 401 response. */
    public static final String AUTH_ERROR_ATTRIBUTE = JwtAuthenticationFilter.class.getName() + ".error";

    /** The verified claims of the request's token (used by logout). */
    public static final String CLAIMS_ATTRIBUTE = JwtAuthenticationFilter.class.getName() + ".claims";

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;
    private final TokenRevocationService revocationService;

    public JwtAuthenticationFilter(
            JwtService jwtService,
            UserDetailsService userDetailsService,
            TokenRevocationService revocationService) {

        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
        this.revocationService = revocationService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain)
            throws ServletException, IOException {

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);

        if (header != null && header.startsWith("Bearer ")) {
            authenticate(request, header.substring("Bearer ".length()).trim());
        }

        filterChain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request, String token) {
        try {
            Claims claims = jwtService.parse(token);

            if (claims.getId() == null || claims.getSubject() == null) {
                reject(request, "TOKEN_INVALID");
                return;
            }
            if (revocationService.isRevoked(claims.getId())) {
                reject(request, "TOKEN_REVOKED");
                return;
            }

            UserDetails user = userDetailsService.loadUserByUsername(claims.getSubject());
            if (!user.isEnabled()) {
                reject(request, "ACCOUNT_DISABLED");
                return;
            }
            if (issuedBeforePasswordChange(claims, user)) {
                reject(request, "TOKEN_REVOKED");
                return;
            }

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

            SecurityContextHolder.getContext().setAuthentication(authentication);
            request.setAttribute(CLAIMS_ATTRIBUTE, claims);

        } catch (ExpiredJwtException expired) {
            reject(request, "TOKEN_EXPIRED");
        } catch (JwtException | IllegalArgumentException | UsernameNotFoundException invalid) {
            reject(request, "TOKEN_INVALID");
        }
    }

    /**
     * A password change ends every session started before it. JWT times are
     * whole seconds, so a token from the same second as the change is kept:
     * that is the new sign-in that usually follows the change.
     */
    /**
     * The token's password stamp (see {@link JwtService#PASSWORD_STAMP_CLAIM})
     * differs from the user's once the password has changed since the token
     * was issued. Tokens from before stamps existed count as 0, which matches
     * users who have never changed their password.
     */
    private static boolean issuedBeforePasswordChange(Claims claims, UserDetails user) {
        if (!(user instanceof CustomUserDetails details)) {
            return false;
        }
        Object stamp = claims.get(JwtService.PASSWORD_STAMP_CLAIM);
        long tokenStamp = stamp instanceof Number number ? number.longValue() : 0;
        return tokenStamp != JwtService.passwordStamp(details.getUser());
    }

    private static void reject(HttpServletRequest request, String reason) {
        SecurityContextHolder.clearContext();
        request.setAttribute(AUTH_ERROR_ATTRIBUTE, reason);
        log.debug("Rejected bearer token on {} {}: {}", request.getMethod(), request.getRequestURI(), reason);
    }
}
