package de.labradt.gateway.framework.gateway.exception;

/**
 * Thrown when the gateway accepted a write request but the downstream
 * adapter or translator failed to apply it, including a write the machine
 * rejected (for example an MQTT Bad status). Surfaced to clients as
 * HTTP 502: the gateway is fine but its upstream dependency failed. The
 * message carries the cause's message so the client sees the reason.
 */
public class GatewayWriteException extends RuntimeException {
    public GatewayWriteException(String idShort, Throwable cause) {
        super("Failed to write value for idShort: " + idShort
                + (cause != null && cause.getMessage() != null ? " (" + cause.getMessage() + ")" : ""), cause);
    }
}
