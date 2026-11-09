package com.athena.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A user as an administrator sees it. Password hashes are never included.
 */
public record AdminUserView(
        Long id,
        String fullName,
        String email,
        List<String> roles,
        boolean enabled,
        String accountNumber,
        String accountStatus,
        BigDecimal balance,
        LocalDateTime createdAt
) {
}
