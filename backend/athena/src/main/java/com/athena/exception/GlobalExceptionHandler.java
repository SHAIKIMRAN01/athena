package com.athena.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Turns errors into RFC 9457 problem-detail JSON, each with a {@code code}
 * field the frontend can switch on.
 *
 * <p>Clients never see stack traces, SQL or exception class names. An
 * unexpected error answers 500 with a random {@code errorId}; the same id is
 * written to the server log next to the full exception, so support can find
 * it without the client learning anything about the internals.
 *
 * <p>Security exceptions are passed back to Spring Security, which answers
 * them with 401 or 403.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** "true" when the response repeats the result of an earlier request with the same key. */
    public static final String IDEMPOTENT_REPLAYED_HEADER = "Idempotent-Replayed";

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ProblemDetail> handleBusinessException(
            BusinessException exception) {

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                exception.getStatus(), exception.getMessage());
        problem.setProperty("code", exception.getCode());

        ResponseEntity.BodyBuilder response = ResponseEntity.status(exception.getStatus());

        // The refusal was saved as a FAILED transaction the customer can look up.
        if (exception.getTransactionId() != null) {
            problem.setProperty("transactionId", exception.getTransactionId());
            problem.setProperty("reference", exception.getReference());
            response.header(IDEMPOTENT_REPLAYED_HEADER, String.valueOf(exception.isReplayed()));
        }

        if (exception instanceof TooManyRequestsException throttled) {
            response.header(HttpHeaders.RETRY_AFTER, String.valueOf(throttled.getRetryAfterSeconds()));
        }

        return response.body(problem);
    }

    /**
     * A database constraint stopped a write, for example two registrations
     * with the same email arriving at the same moment. The constraint did its
     * job; the client gets a 409 instead of a 500.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrityViolation(
            DataIntegrityViolationException exception) {

        log.warn("Database constraint rejected a write: {}", exception.getMostSpecificCause().getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "The request conflicts with existing data");
        problem.setProperty("code", "DATA_CONFLICT");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /**
     * Anything not handled above. The details stay in the server log.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception exception) throws Exception {

        // @PreAuthorize refusals and authentication failures belong to Spring
        // Security (401/403, and the ACCESS_DENIED audit record).
        if (exception instanceof AccessDeniedException
                || exception instanceof AuthenticationException) {
            throw exception;
        }

        String errorId = UUID.randomUUID().toString();
        log.error("Unexpected error {}", errorId, exception);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Something went wrong on our side. No money was moved by this request.");
        problem.setProperty("code", "INTERNAL_ERROR");
        problem.setProperty("errorId", errorId);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }

    /**
     * Spring's own errors (404 unknown path, 405 wrong method, 415 wrong
     * content type, ...) get a {@code code} too, named after the status.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception,
            Object body,
            HttpHeaders headers,
            HttpStatusCode statusCode,
            WebRequest request) {

        ResponseEntity<Object> response =
                super.handleExceptionInternal(exception, body, headers, statusCode, request);

        if (response != null && response.getBody() instanceof ProblemDetail problem
                && (problem.getProperties() == null || !problem.getProperties().containsKey("code"))) {
            HttpStatus status = HttpStatus.resolve(statusCode.value());
            problem.setProperty("code", status == null ? "ERROR" : status.name());
        }

        return response;
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        Map<String, String> errors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
                errors.putIfAbsent(error.getField(), error.getDefaultMessage()));

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                status, "Request validation failed");
        problem.setProperty("code", "VALIDATION_FAILED");
        problem.setProperty("errors", errors);

        return handleExceptionInternal(exception, problem, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        String parameter = exception instanceof MethodArgumentTypeMismatchException mismatch
                ? mismatch.getName()
                : exception.getPropertyName();

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                status, "Invalid value for parameter '" + parameter + "'");
        problem.setProperty("code", "INVALID_PARAMETER");

        return handleExceptionInternal(exception, problem, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        // A chunked body without Content-Length is cut off by RequestSizeLimitFilter
        // while JSON is being read; report that as 413, not as malformed JSON.
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof PayloadTooLargeException tooLarge) {
                ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                        HttpStatus.CONTENT_TOO_LARGE, tooLarge.getMessage());
                problem.setProperty("code", "PAYLOAD_TOO_LARGE");
                return handleExceptionInternal(
                        exception, problem, headers, HttpStatus.CONTENT_TOO_LARGE, request);
            }
        }

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                status, "Request body is missing or is not valid JSON");
        problem.setProperty("code", "MALFORMED_REQUEST");

        return handleExceptionInternal(exception, problem, headers, status, request);
    }
}
