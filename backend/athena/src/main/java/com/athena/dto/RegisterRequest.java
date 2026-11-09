package com.athena.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class RegisterRequest {

    // Letters (any language), spaces, apostrophes, dots and hyphens. Rejects
    // markup such as <script>, digits and control characters.
    @NotBlank(message = "Full name is required")
    @Size(max = 100, message = "Full name must be at most 100 characters")
    @Pattern(regexp = "^[\\p{L}\\p{M}][\\p{L}\\p{M} .'-]*$",
             message = "Full name may contain only letters, spaces, apostrophes, dots and hyphens")
    private String fullName;

    @NotBlank(message = "Email is required")
    @Email(message = "Enter a valid email")
    @Size(max = 150, message = "Email must be at most 150 characters")
    private String email;

    @NotBlank(message = "Password is required")
    @Size(min = 8, max = 72,
          message = "Password must contain 8 to 72 characters")
    @Pattern(regexp = "^(?=.*\\p{L})(?=.*\\d).*$",
             message = "Password must contain at least one letter and one digit")
    private String password;

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}