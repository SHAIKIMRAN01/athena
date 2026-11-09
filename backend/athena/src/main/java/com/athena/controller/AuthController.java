package com.athena.controller;

import com.athena.dto.LoginRequest;
import com.athena.dto.LoginResponse;
import com.athena.dto.RegisterRequest;
import com.athena.dto.RegisterResponse;
import com.athena.security.CustomUserDetails;
import com.athena.security.JwtAuthenticationFilter;
import com.athena.security.RateLimitService;
import com.athena.service.AuthService;

import io.jsonwebtoken.Claims;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final RateLimitService rateLimitService;

    public AuthController(AuthService authService, RateLimitService rateLimitService) {
        this.authService = authService;
        this.rateLimitService = rateLimitService;
    }

    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(
            @Valid @RequestBody RegisterRequest request,
            HttpServletRequest http) {

        rateLimitService.checkRegistration(http.getRemoteAddr());

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(authService.register(request, http.getRemoteAddr()));
    }

    @PostMapping("/login")
    public LoginResponse login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest http) {

        return authService.login(request, http.getRemoteAddr());
    }

    /**
     * Signs out the token this request was made with. Requires a valid token
     * (see SecurityConfig), so the claims are always present.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            HttpServletRequest http) {

        Claims claims = (Claims) http.getAttribute(JwtAuthenticationFilter.CLAIMS_ATTRIBUTE);
        authService.logout(userDetails, claims, http.getRemoteAddr());

        return ResponseEntity.noContent().build();
    }
}
