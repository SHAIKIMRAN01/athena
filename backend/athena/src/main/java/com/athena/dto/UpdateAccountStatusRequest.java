package com.athena.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Freeze or unfreeze a customer account. A FROZEN account cannot send,
 * receive, deposit or withdraw money.
 */
public record UpdateAccountStatusRequest(
        @NotBlank(message = "status is required")
        @Pattern(regexp = "ACTIVE|FROZEN", message = "status must be ACTIVE or FROZEN")
        String status,

        @jakarta.validation.constraints.Size(max = 200, message = "reason must be at most 200 characters")
        String reason
) {
}
