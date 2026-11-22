package de.labradt.gateway.framework.gateway;

import de.labradt.gateway.framework.adapter.AdapterRegistry;
import de.labradt.gateway.framework.gateway.exception.AdapterUnavailableException;
import de.labradt.gateway.framework.gateway.exception.GatewayWriteException;
import de.labradt.gateway.framework.model.AdapterMapping;
import de.labradt.gateway.framework.model.RawValue;
import de.labradt.gateway.framework.model.SubmodelElement;
import de.labradt.gateway.framework.translation.TranslatorRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class GatewayService implements IGateway {

    private static final Logger log = LoggerFactory.getLogger(GatewayService.class);

    private final AdapterRegistry adapterRegistry;
    private final TranslatorRegistry translatorRegistry;
    private final MappingRegistry mappingRegistry;

    public GatewayService(AdapterRegistry adapterRegistry,
                          TranslatorRegistry translatorRegistry,
                          MappingRegistry mappingRegistry) {
        this.adapterRegistry = adapterRegistry;
        this.translatorRegistry = translatorRegistry;
        this.mappingRegistry = mappingRegistry;
    }

    @Override
    public SubmodelElement read(String routingKey) {
        AdapterMapping mapping = mappingRegistry.get(routingKey);
        ensureAdapterAvailable(mapping.getMachineId());

        RawValue raw = adapterRegistry.getAdapter(mapping.getMachineId())
                .readAddress(mapping.getSourceAddress());

        return translatorRegistry.getTranslator(mapping.getProtocol())
                .translateValue(raw, mapping);
    }

    @Override
    public void write(String routingKey, Object value) {
        AdapterMapping mapping = mappingRegistry.get(routingKey);
        ensureAdapterAvailable(mapping.getMachineId());

        // Wrap the new value in a SubmodelElement so the translator can run
        // its reverse pipeline (value-map reversal, unit conversion, type cast).
        SubmodelElement element = new SubmodelElement();
        element.setIdShort(mapping.getIdShort());
        element.setValue(value == null ? null : value.toString());
        element.setValueType(mapping.getValueType());
        element.setUnit(mapping.getUnit());

        try {
            RawValue raw = translatorRegistry.getTranslator(mapping.getProtocol())
                    .reverseTranslateElement(element, mapping);

            adapterRegistry.getAdapter(mapping.getMachineId())
                    .writeAddress(raw.getAddress(), raw.getValue());

            log.debug("Wrote value to {} ({})", routingKey, mapping.getSourceAddress());
        } catch (AdapterUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new GatewayWriteException(routingKey, e);
        }
    }

    private void ensureAdapterAvailable(String machineId) {
        if (!adapterRegistry.hasAdapter(machineId)) {
            throw new AdapterUnavailableException(machineId);
        }
        if (!adapterRegistry.getAdapter(machineId).isConnected()) {
            throw new AdapterUnavailableException(machineId);
        }
    }
}
