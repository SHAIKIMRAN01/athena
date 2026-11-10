package com.athena.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A signed-in user's request to change their own password. The new password
 * follows the same rules as at registration.
 */
public record ChangePasswordRequest(

        @NotBlank(message = "Current password is required")
        @Size(max = 200, message = "Current password is too long")
        String currentPassword,

        @NotBlank(message = "New password is required")
        @Size(min = 8, max = 72, message = "Password must contain 8 to 72 characters")
        @Pattern(regexp = "^(?=.*\\p{L})(?=.*\\d).*$",
                 message = "Password must contain at least one letter and one digit")
        String newPassword) {
}
