package com.athena.dto;

import java.util.List;

/**
 * The signed-in user, as the frontend needs it to choose which pages to show.
 * {@code accountNumber} is null for administrators and auditors, who have no
 * customer account.
 */
public record CurrentUserResponse(
        Long id,
        String fullName,
        String email,
        List<String> roles,
        String accountNumber
) {
}
