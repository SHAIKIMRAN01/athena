package com.athena.service;

import com.athena.exception.InvalidRequestException;

import org.springframework.data.domain.Sort;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared checks for list endpoints: paging limits, the sort whitelist, date
 * ranges and search terms. User input never becomes part of a query string;
 * it is only ever compared through bound parameters.
 */
final class QuerySupport {

    static final int MAX_PAGE_SIZE = 100;
    static final int MAX_SEARCH_LENGTH = 100;

    private static final Set<String> SORTABLE_FIELDS = Set.of("createdAt", "amount");
    private static final Pattern REFERENCE = Pattern.compile("^TXN0*(\\d{1,18})$");

    private QuerySupport() {}

    static void checkPage(int page, int size) {
        if (page < 0) {
            throw new InvalidRequestException("INVALID_PAGE", "page must be 0 or more");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidRequestException(
                    "INVALID_PAGE_SIZE", "size must be between 1 and " + MAX_PAGE_SIZE);
        }
    }

    static void checkDateRange(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new InvalidRequestException(
                    "INVALID_DATE_RANGE", "from must be on or before to");
        }
    }

    /**
     * @param sort "createdAt" or "amount", optionally followed by ",asc" or ",desc"
     */
    static Sort transactionSort(String sort) {

        String[] parts = (sort == null || sort.isBlank() ? "createdAt,desc" : sort).split(",");
        String field = parts[0].trim();
        String direction = parts.length > 1 ? parts[1].trim().toLowerCase(Locale.ROOT) : "desc";

        if (parts.length > 2 || !SORTABLE_FIELDS.contains(field)
                || !(direction.equals("asc") || direction.equals("desc"))) {
            throw new InvalidRequestException("INVALID_SORT",
                    "sort must be createdAt or amount, optionally followed by ,asc or ,desc");
        }

        Sort.Direction order = Sort.Direction.fromString(direction);
        // The id breaks ties, so paging through equal timestamps or amounts is stable.
        return Sort.by(order, field).and(Sort.by(order, "id"));
    }

    /**
     * Trimmed search text, or empty if none was given.
     */
    static Optional<String> searchTerm(String search) {
        if (search == null || search.isBlank()) {
            return Optional.empty();
        }
        String term = search.trim();
        if (term.length() > MAX_SEARCH_LENGTH) {
            throw new InvalidRequestException("INVALID_SEARCH",
                    "search must be at most " + MAX_SEARCH_LENGTH + " characters");
        }
        return Optional.of(term);
    }

    /**
     * The transaction id in a reference such as "TXN0000000042" (any case).
     */
    static Optional<Long> referenceId(String term) {
        Matcher matcher = REFERENCE.matcher(term.toUpperCase(Locale.ROOT));
        return matcher.matches() ? Optional.of(Long.parseLong(matcher.group(1))) : Optional.empty();
    }

    /**
     * A LIKE pattern that matches {@code term} anywhere, with the wildcards
     * % and _ in the term escaped (escape character: backslash).
     */
    static String containsPattern(String term) {
        String escaped = term.toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
