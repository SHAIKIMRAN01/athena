package com.athena.security;

import com.jayway.jsonpath.JsonPath;
import com.athena.ApiIntegrationTest;
import com.athena.dto.RegisterResponse;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.SecretKey;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Access tokens: only tokens this server signed, that have not expired and
 * have not been signed out, are accepted. THREATS.md: T1 (token forgery),
 * T2 (stolen or signed-out token), T3 (disabled user keeps access).
 */
class SessionSecurityIntegrationTest extends ApiIntegrationTest {

    private static final String ME = "/api/accounts/me";

    @Autowired
    private JwtService jwtService;

    /**
     * The key the running server signs with. (Reading app.jwt.secret again
     * would not work: the test profile's ${random.value} is new on every read.)
     */
    private SecretKey serverKey() {
        return (SecretKey) ReflectionTestUtils.getField(jwtService, "signingKey");
    }

    @Test
    void aValidTokenWorks() throws Exception {
        register("Alice");
        getAs(login("Alice"), ME).andExpect(status().isOk());
    }

    @Test
    void requestsWithoutAUsableTokenAreRefusedWithAReason() throws Exception {
        mockMvc.perform(get(ME))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        getAs("not-a-jwt", ME)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
    }

    @Test
    void aTokenSignedWithAnotherKeyIsRefused() throws Exception {
        register("Alice");
        SecretKey otherKey = Keys.hmacShaKeyFor(new byte[48]);

        String token = Jwts.builder()
                .id(UUID.randomUUID().toString())
                .issuer(JwtService.ISSUER)
                .subject(emailOf("Alice"))
                .expiration(Date.from(Instant.now().plusSeconds(600)))
                .signWith(otherKey, Jwts.SIG.HS256)
                .compact();

        getAs(token, ME)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
    }

    @Test
    void aTokenWhosePayloadWasEditedIsRefused() throws Exception {
        register("Alice");
        register("Bob");
        String[] parts = login("Alice").split("\\.");

        // Swap the payload for one naming Bob, keeping Alice's signature.
        String bobPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"jti\":\"x\",\"iss\":\"athena\",\"sub\":\"" + emailOf("Bob")
                        + "\",\"exp\":4102444800}").getBytes());

        getAs(parts[0] + "." + bobPayload + "." + parts[2], ME)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
    }

    @Test
    void anUnsignedTokenIsRefused() throws Exception {
        register("Alice");
        String header = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\"}".getBytes());
        String payload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"jti\":\"x\",\"iss\":\"athena\",\"sub\":\"" + emailOf("Alice")
                        + "\",\"exp\":4102444800}").getBytes());

        getAs(header + "." + payload + ".", ME)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
    }

    @Test
    void anExpiredTokenIsRefusedAsExpired() throws Exception {
        register("Alice");
        String token = Jwts.builder()
                .id(UUID.randomUUID().toString())
                .issuer(JwtService.ISSUER)
                .subject(emailOf("Alice"))
                .issuedAt(Date.from(Instant.now().minusSeconds(1200)))
                .expiration(Date.from(Instant.now().minusSeconds(300)))
                .signWith(serverKey(), Jwts.SIG.HS256)
                .compact();

        getAs(token, ME)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
    }

    @Test
    void aTokenFromAnotherIssuerOrWithoutAnIdIsRefused() throws Exception {
        register("Alice");
        Date expiry = Date.from(Instant.now().plusSeconds(600));

        String otherIssuer = Jwts.builder()
                .id(UUID.randomUUID().toString())
                .issuer("someone-else")
                .subject(emailOf("Alice"))
                .expiration(expiry)
                .signWith(serverKey(), Jwts.SIG.HS256)
                .compact();
        String withoutId = Jwts.builder()
                .issuer(JwtService.ISSUER)
                .subject(emailOf("Alice"))
                .expiration(expiry)
                .signWith(serverKey(), Jwts.SIG.HS256)
                .compact();

        getAs(otherIssuer, ME).andExpect(status().isUnauthorized());
        getAs(withoutId, ME).andExpect(status().isUnauthorized());
    }

    @Test
    void rolesWrittenIntoATokenAreIgnored() throws Exception {
        register("Alice");

        // Even a correctly signed token cannot grant a role: roles come from the database.
        String token = Jwts.builder()
                .id(UUID.randomUUID().toString())
                .issuer(JwtService.ISSUER)
                .subject(emailOf("Alice"))
                .claim("roles", List.of("ROLE_ADMIN"))
                .expiration(Date.from(Instant.now().plusSeconds(600)))
                .signWith(serverKey(), Jwts.SIG.HS256)
                .compact();

        getAs(token, "/api/admin/users").andExpect(status().isForbidden());
    }

    @Test
    void logoutEndsThatSessionOnly() throws Exception {
        register("Alice");
        String first = login("Alice");
        String second = login("Alice");

        mockMvc.perform(post("/api/auth/logout").header(AUTHORIZATION, "Bearer " + first))
                .andExpect(status().isNoContent());

        getAs(first, ME)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_REVOKED"));
        getAs(second, ME).andExpect(status().isOk());

        assertThat(sessionAuditLog()).extracting(AuditRow::action)
                .containsExactly("REGISTER", "LOGIN", "LOGIN", "LOGOUT");
    }

    @Test
    void logoutNeedsAValidToken() throws Exception {
        mockMvc.perform(post("/api/auth/logout")).andExpect(status().isUnauthorized());
    }

    @Test
    void disablingAUserStopsTokensTheyAlreadyHold() throws Exception {
        RegisterResponse alice = register("Alice");
        String token = login("Alice");

        jdbcTemplate.update("UPDATE app_users SET enabled = FALSE WHERE id = ?", alice.getUserId());

        getAs(token, ME)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
    }

    @Test
    void loginReturnsRolesAndAShortExpiry() throws Exception {
        register("Alice");

        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"%s\", \"password\": \"%s\"}".formatted(emailOf("Alice"), PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<List<String>>read(body, "$.roles")).containsExactly("USER");
        assertThat(JsonPath.<Integer>read(body, "$.expiresIn")).isEqualTo(900);
    }

    @Test
    void theServerRefusesToStartWithAWeakSigningKey() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new JwtService("", 1000, Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new JwtService("c2hvcnQ=", 1000, Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes");
    }
}
