package de.labradt.gateway.framework.translation;

import de.labradt.gateway.framework.model.AdapterMapping;
import de.labradt.gateway.framework.model.ProtocolType;
import de.labradt.gateway.framework.model.RawValue;
import de.labradt.gateway.framework.model.SubmodelElement;

/**
 * Protocol translation SPI: converts between protocol payloads and typed
 * {@link SubmodelElement}s.
 *
 * <p>ISO 23247-2 traceability: realizes the cross-system entity's data
 * translation FE (§6.5.3, "protocol conversion, syntax adaptation, and
 * semantic awareness") and contributes to the digital twin entity's digital
 * representation FE (§6.3.1.3).
 */
public interface ITranslationLayer {

    SubmodelElement translateValue(RawValue rawValue, AdapterMapping mapping);
    RawValue reverseTranslateElement(SubmodelElement submodelElement, AdapterMapping mapping);
    boolean supportsProtocol(ProtocolType protocol);
    ProtocolType getProtocol();
}
