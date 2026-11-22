package de.labradt.gateway.framework.gateway;

import de.labradt.gateway.framework.model.SubmodelElement;

/**
 * SPI for components that want to observe activity in the
 * {@link SubmodelStore}. Beans implementing this interface are auto-collected
 * and invoked by the store.
 *
 * <p>Two callbacks exist for two distinct concerns:
 *
 * <ul>
 *   <li>{@link #onElementStored(SubmodelElement)} fires only on a
 *       meaningful change (value or status differs from the previous
 *       cached element). Use this when you only care about real changes,
 *       e.g. a data recorder appending one row per real update.</li>
 *
 *   <li>{@link #onElementObserved(SubmodelElement)} fires on every
 *       successful poll, even when the value did not change. Use this when
 *       you need to know the gateway is still seeing this signal,
 *       e.g. a live UI refreshing freshness indicators.</li>
 * </ul>
 *
 * Both callbacks have default no-op implementations, so listeners only
 * override the one they care about.
 */
public interface SubmodelStoreListener {

    /**
     * Called when the value or statusCode of an element actually changed
     * since the previously cached version. Suppressed by deadband filtering.
     */
    default void onElementStored(SubmodelElement element) {}

    /**
     * Called on every put into the store, including no-op puts where the
     * value did not change. Used to propagate the gateway's "I just saw
     * this signal" pulse to consumers that need it (e.g. freshness UIs).
     */
    default void onElementObserved(SubmodelElement element) {}
}
