package com.athena.security;

import jakarta.servlet.DispatcherType;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * HTTP security: stateless JWT authentication, role rules per URL, security
 * headers, CORS, and the abuse filters (rate limit, request size).
 *
 * <p>Filter order for an API request: rate limit, then request size, then
 * JWT authentication, then the URL rules below, then the controller, whose
 * {@code @PreAuthorize} checks the role a second time.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * Only this site's own scripts, styles and API. No inline scripts, no
     * framing, no plugins: an injected script tag would not run, and the
     * pages cannot be embedded for clickjacking.
     */
    static final String CONTENT_SECURITY_POLICY = String.join("; ",
            "default-src 'self'",
            "script-src 'self'",
            "style-src 'self'",
            "img-src 'self' data:",
            "font-src 'self'",
            "connect-src 'self'",
            "object-src 'none'",
            "base-uri 'none'",
            "frame-ancestors 'none'",
            "form-action 'self'");

    private final CustomUserDetailsService userDetailsService;
    private final JwtService jwtService;
    private final TokenRevocationService revocationService;
    private final RateLimitService rateLimitService;
    private final AuditingAccessDeniedHandler accessDeniedHandler;
    private final long maxRequestBytes;
    private final List<String> allowedOrigins;

    public SecurityConfig(
            CustomUserDetailsService userDetailsService,
            JwtService jwtService,
            TokenRevocationService revocationService,
            RateLimitService rateLimitService,
            AuditingAccessDeniedHandler accessDeniedHandler,
            @Value("${app.security.max-request-bytes:16384}") long maxRequestBytes,
            @Value("${app.security.cors.allowed-origins:}") String allowedOrigins) {

        this.userDetailsService = userDetailsService;
        this.jwtService = jwtService;
        this.revocationService = revocationService;
        this.rateLimitService = rateLimitService;
        this.accessDeniedHandler = accessDeniedHandler;
        this.maxRequestBytes = maxRequestBytes;
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider() {

        DaoAuthenticationProvider provider =
                new DaoAuthenticationProvider(userDetailsService);

        provider.setPasswordEncoder(passwordEncoder());

        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration configuration)
            throws Exception {

        return configuration.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http)
            throws Exception {

        http
            // CSRF attacks ride on cookies the browser sends by itself. Athena
            // uses no cookies: the token is sent explicitly in the Authorization header.
            .csrf(csrf -> csrf.disable())

            .cors(cors -> cors.configurationSource(corsConfigurationSource()))

            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )

            .authenticationProvider(authenticationProvider())

            .headers(headers -> headers
                .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER))
                .frameOptions(frame -> frame.deny())
                .httpStrictTransportSecurity(hsts -> hsts
                    .includeSubDomains(true)
                    .maxAgeInSeconds(31_536_000))
                .addHeaderWriter(new StaticHeadersWriter("Permissions-Policy",
                    "camera=(), microphone=(), geolocation=(), payment=(), usb=()"))
                .addHeaderWriter(new StaticHeadersWriter("Cross-Origin-Opener-Policy", "same-origin"))
            )

            .authorizeHttpRequests(auth -> auth
                // Let Spring Boot render error responses; otherwise an
                // unexpected 500 is replaced by a security rejection.
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/health").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/oversight/**").hasAnyRole("ADMIN", "AUDITOR")
                .requestMatchers("/api/**").authenticated()
                // Everything else is the single-page app's static files.
                .anyRequest().permitAll()
            )

            // Missing, invalid, expired or revoked token: 401 with a reason code.
            // A valid token without the required role: 403, recorded in the audit log.
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint(new ProblemJsonAuthenticationEntryPoint())
                .accessDeniedHandler(accessDeniedHandler)
            )

            .formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable())
            .logout(logout -> logout.disable())

            // Added in this order, they run in this order.
            .addFilterBefore(new RateLimitFilter(rateLimitService),
                UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(new RequestSizeLimitFilter(maxRequestBytes),
                UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(
                new JwtAuthenticationFilter(jwtService, userDetailsService, revocationService),
                UsernamePasswordAuthenticationFilter.class
            );

        return http.build();
    }

    /**
     * No cross-origin access unless CORS_ALLOWED_ORIGINS lists origins: none
     * is needed when Spring Boot serves the frontend itself. When the website
     * is hosted elsewhere (Vercel), list its exact address, for example
     * https://athena.vercel.app. A "*" inside an entry matches preview
     * addresses such as https://athena-git-*-myteam.vercel.app; never use
     * https://*.vercel.app, which would admit every site on Vercel.
     */
    private CorsConfigurationSource corsConfigurationSource() {

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();

        if (!allowedOrigins.isEmpty()) {
            CorsConfiguration configuration = new CorsConfiguration();
            configuration.setAllowedOriginPatterns(allowedOrigins);
            configuration.setAllowedMethods(List.of("GET", "POST", "PATCH", "OPTIONS"));
            configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key"));
            configuration.setExposedHeaders(List.of("Idempotent-Replayed", "Retry-After"));
            configuration.setAllowCredentials(false);
            configuration.setMaxAge(3600L);
            source.registerCorsConfiguration("/api/**", configuration);
        }

        return source;
    }
}
