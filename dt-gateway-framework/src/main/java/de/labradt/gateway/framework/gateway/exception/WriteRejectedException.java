package de.labradt.gateway.framework.gateway.exception;

/**
 * Thrown when a write is rejected for a policy reason (not a technical failure).
 * The canonical case is "safe mode is active". Surfaced to clients as HTTP 403.
 */
public class WriteRejectedException extends RuntimeException {
    public WriteRejectedException(String reason) {
        super(reason);
    }
}
