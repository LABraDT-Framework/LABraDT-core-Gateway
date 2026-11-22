package de.labradt.gateway.framework.gateway.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

/**
 * Translates framework exceptions into HTTP responses with a uniform
 * error envelope. This is the single place where the REST contract's
 * error semantics live.
 *
 *   404  idShort is not configured
 *   503  mapping exists but no adapter is registered for its protocol
 *   502  adapter / translator failed while servicing the request
 *   400  malformed request payload (illegal argument)
 *   500  anything else
 */
@RestControllerAdvice
public class GatewayExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayExceptionHandler.class);

    @ExceptionHandler(MappingNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(MappingNotFoundException ex) {
        return error(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(AdapterUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleUnavailable(AdapterUnavailableException ex) {
        log.warn("Adapter unavailable: {}", ex.getMessage());
        return error(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
    }

    @ExceptionHandler(GatewayWriteException.class)
    public ResponseEntity<Map<String, Object>> handleWriteFailure(GatewayWriteException ex) {
        log.error("Write failure: {}", ex.getMessage(), ex.getCause());
        return error(HttpStatus.BAD_GATEWAY, ex.getMessage());
    }

    @ExceptionHandler(WriteRejectedException.class)
    public ResponseEntity<Map<String, Object>> handleRejected(WriteRejectedException ex) {
        log.warn("Write rejected: {}", ex.getMessage());
        return error(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException ex) {
        return error(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleAny(Exception ex) {
        log.error("Unhandled error", ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Internal gateway error");
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "status", status.value(),
                "error", status.getReasonPhrase(),
                "message", message == null ? "" : message,
                "timestamp", Instant.now().toString()
        ));
    }
}
