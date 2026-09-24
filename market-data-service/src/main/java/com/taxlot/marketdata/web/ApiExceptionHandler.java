package com.taxlot.marketdata.web;

import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps domain failures to HTTP status codes.
 *
 * <p>Without this, a rejected {@code days=0} surfaces as a 500, which tells a caller the server
 * is broken when in fact their request was. The distinction matters for the retry behaviour of
 * anything calling this service: a 4xx should not be retried, a 5xx should.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** Caller sent something invalid — bad ticker, non-positive day count. */
    @ExceptionHandler({IllegalArgumentException.class, ConstraintViolationException.class})
    public ResponseEntity<ApiError> badRequest(Exception e) {
        log.debug("Rejected a bad request: {}", e.getMessage());
        return ResponseEntity.badRequest().body(ApiError.of(HttpStatus.BAD_REQUEST, e.getMessage()));
    }

    /**
     * The request was well-formed but the service is not in a state to serve it — for example,
     * simulating prices before any price history exists. 409 rather than 400: the caller did
     * nothing wrong, and the same request may succeed once the data is seeded.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> conflict(IllegalStateException e) {
        log.warn("Request could not be served in the current state: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of(HttpStatus.CONFLICT, e.getMessage()));
    }

    public record ApiError(Instant timestamp, int status, String error, String message) {

        static ApiError of(HttpStatus status, String message) {
            return new ApiError(Instant.now(), status.value(), status.getReasonPhrase(), message);
        }
    }
}
