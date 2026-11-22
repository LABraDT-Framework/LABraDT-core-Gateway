package de.labradt.gateway.framework.gateway.exception;

/**
 * Thrown when no adapter is registered for a machineId, or when the adapter
 * is currently disconnected. Surfaced to clients as HTTP 503.
 */
public class AdapterUnavailableException extends RuntimeException {
    public AdapterUnavailableException(String machineId) {
        super("No adapter available for machine: " + machineId);
    }

    public AdapterUnavailableException(String machineId, Throwable cause) {
        super("Adapter for machine " + machineId + " is not reachable", cause);
    }
}
