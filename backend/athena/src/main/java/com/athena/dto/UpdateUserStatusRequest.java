package com.athena.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Enable or disable a user. A disabled user cannot sign in, and tokens they
 * already hold stop working on their next request.
 */
public record UpdateUserStatusRequest(
        @NotNull(message = "enabled is required") Boolean enabled
) {
}
