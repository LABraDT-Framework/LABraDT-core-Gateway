package de.labradt.gateway.framework.gateway.exception;

/**
 * Thrown when a request references an idShort that has no AdapterMapping
 * configured. Surfaced to clients as HTTP 404.
 */
public class MappingNotFoundException extends RuntimeException {
    public MappingNotFoundException(String idShort) {
        super("No mapping found for idShort: " + idShort);
    }
}
