package de.labradt.gateway.framework.model;

/**
 * Single source of truth for staleness: staleness is a property of the
 * machine, displayed on its elements.
 *
 * <p>An element is served as {@code STALE} when the machine it belongs to is
 * not currently delivering data (transport down, or, for broker-mediated
 * protocols, silent beyond its quiet window). Whether the element's value
 * changes plays no role: a clamp force legitimately held at the same value
 * for hours on a living machine is fresh; the same value from a machine the
 * gateway cannot hear is last-known, and is labelled accordingly.
 *
 * <p>Elements with category {@code CONSTANT} are exempt: a dead link does not
 * invalidate a serial number.
 *
 * <p>All methods are pure: {@link #applyTo} returns a flagged copy; the cached
 * element in {@code SubmodelStore} is never modified.
 */
public final class StalenessPolicy {

    public static final String STALE_STATUS = "STALE";

    private StalenessPolicy() {}

    public static boolean isStale(SubmodelElement element, boolean machineAlive) {
        if (element == null) return false;
        if (element.constantCategory()) return false;
        return !machineAlive;
    }

    /**
     * Returns the element unchanged when its machine is alive (or the element
     * is CONSTANT), or a copy with {@code statusCode = "STALE"} otherwise.
     */
    public static SubmodelElement applyTo(SubmodelElement element, boolean machineAlive) {
        if (!isStale(element, machineAlive)) return element;
        SubmodelElement copy = element.copy();
        copy.setStatusCode(STALE_STATUS);
        return copy;
    }
}
