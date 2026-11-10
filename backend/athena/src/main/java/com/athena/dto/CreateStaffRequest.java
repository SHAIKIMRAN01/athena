package com.athena.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * An administrator adds a staff member: another administrator (ADMIN) or an
 * auditor (AUDITOR), the role names the API shows everywhere.
 * The new user signs in with the initial password, which the administrator
 * passes on privately, and then changes it on the Profile page.
 */
public record CreateStaffRequest(

        @NotBlank(message = "Full name is required")
        @Size(max = 100, message = "Full name must be at most 100 characters")
        @Pattern(regexp = "^[\\p{L}\\p{M}][\\p{L}\\p{M} .'-]*$",
                message = "Full name may contain only letters, spaces, apostrophes, dots and hyphens")
        String fullName,

        @NotBlank(message = "Email is required")
        @Email(message = "Enter a valid email")
        @Size(max = 150, message = "Email must be at most 150 characters")
        String email,

        @NotBlank(message = "Role is required")
        @Pattern(regexp = "ADMIN|AUDITOR", message = "Role must be ADMIN or AUDITOR")
        String role,

        @NotBlank(message = "Initial password is required")
        @Size(min = 12, max = 72, message = "Initial password must be 12 to 72 characters")
        @Pattern(regexp = "^(?=.*\\p{L})(?=.*\\d).*$",
                message = "Initial password must contain a letter and a digit")
        String initialPassword) {
}
