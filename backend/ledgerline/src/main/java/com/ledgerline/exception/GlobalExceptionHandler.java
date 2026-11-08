package com.ledgerline.exception;

import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns errors into RFC 9457 problem-detail JSON, each with a {@code code}
 * field. Security exceptions are not handled here: Spring Security still
 * answers those with 401/403.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

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

        return response.body(problem);
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

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                status, "Request body is missing or is not valid JSON");
        problem.setProperty("code", "MALFORMED_REQUEST");

        return handleExceptionInternal(exception, problem, headers, status, request);
    }
}
