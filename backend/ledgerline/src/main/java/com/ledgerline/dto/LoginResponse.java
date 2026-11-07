package com.ledgerline.dto;

public class LoginResponse {

    private String accessToken;
    private String tokenType;
    private long expiresIn;
    private String email;

    public LoginResponse(
            String accessToken,
            String email,
            long expiresIn) {

        this.accessToken = accessToken;
        this.tokenType = "Bearer";
        this.email = email;
        this.expiresIn = expiresIn;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public String getTokenType() {
        return tokenType;
    }

    public long getExpiresIn() {
        return expiresIn;
    }

    public String getEmail() {
        return email;
    }
}