package de.labradt.gateway.framework.translation;

import de.labradt.gateway.framework.model.ProtocolType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class TranslatorRegistry {

    private static final Logger log = LoggerFactory.getLogger(TranslatorRegistry.class);

    private final Map<ProtocolType, ITranslationLayer> translators = new HashMap<>();

    public TranslatorRegistry(List<ITranslationLayer> discoveredTranslators) {
        for (ITranslationLayer translator : discoveredTranslators) {
            translators.put(translator.getProtocol(), translator);
            log.info("Registered translator for protocol '{}'", translator.getProtocol());
        }
    }

    public ITranslationLayer getTranslator(ProtocolType protocol) {
        ITranslationLayer translator = translators.get(protocol);
        if (translator == null) {
            throw new IllegalArgumentException("No translator registered for protocol: " + protocol);
        }
        return translator;
    }

    public boolean hasTranslator(ProtocolType protocol) {
        return translators.containsKey(protocol);
    }
}
