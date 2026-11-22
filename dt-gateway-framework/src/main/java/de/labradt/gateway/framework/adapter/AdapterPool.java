package de.labradt.gateway.framework.adapter;

import java.util.Collection;

/**
 * Extensibility seam: each protocol autoconfig creates one AdapterPool bean
 * containing all the adapters it manages. AdapterRegistry collects every pool.
 */
public interface AdapterPool {
    Collection<ICPPSAdapter> all();
}
