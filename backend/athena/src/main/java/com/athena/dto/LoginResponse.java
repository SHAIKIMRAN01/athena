package com.athena.dto;

import java.util.List;

public class LoginResponse {

    private final String accessToken;
    private final String tokenType;
    private final long expiresIn;
    private final String email;
    private final List<String> roles;

    public LoginResponse(
            String accessToken,
            String email,
            long expiresIn,
            List<String> roles) {

        this.accessToken = accessToken;
        this.tokenType = "Bearer";
        this.email = email;
        this.expiresIn = expiresIn;
        this.roles = roles;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public String getTokenType() {
        return tokenType;
    }

    /** Seconds until the token expires. */
    public long getExpiresIn() {
        return expiresIn;
    }

    public String getEmail() {
        return email;
    }

    /** USER, ADMIN and/or AUDITOR. */
    public List<String> getRoles() {
        return roles;
    }
}
