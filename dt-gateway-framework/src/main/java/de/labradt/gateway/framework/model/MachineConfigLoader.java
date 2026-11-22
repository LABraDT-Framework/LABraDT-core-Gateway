package de.labradt.gateway.framework.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Loads one {@link MachineConfig} per JSON file in the configured folder.
 * No composition, no templates: each file states its mappings explicitly,
 * and the loader parses, validates loudly, and shares the result.
 *
 * <p>Validation failures (missing machineId, duplicate machineId, missing
 * endpoint, empty mappings) reject the file with a clear [CONFIG] error line
 * so a broken file never half-loads silently.
 */
@Component
public class MachineConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(MachineConfigLoader.class);

    private final MachineConfigProperties properties;
    private final ResourcePatternResolver resourceResolver;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Loaded once at startup and shared by every consumer (MappingRegistry,
     *  the protocol adapter autoconfigs) so files are parsed and logged once. */
    private List<MachineConfig> cached;
    private final Map<String,String> loadedFiles = new LinkedHashMap<>();
    private final List<String> loadingErrors = new ArrayList<>();

    public synchronized Map<String,Object> configurationStatus() {
        loadAll();
        return Map.of("files", Map.copyOf(loadedFiles), "errors", List.copyOf(loadingErrors));
    }

    public MachineConfigLoader(MachineConfigProperties properties,
                               ResourcePatternResolver resourceResolver) {
        this.properties = properties;
        this.resourceResolver = resourceResolver;
    }

    public synchronized List<MachineConfig> loadAll() {
        if (cached != null) return cached;

        List<MachineConfig> configs = new ArrayList<>();
        String path = properties.getConfigPath();

        if (path == null || path.isBlank()) {
            log.warn("[CONFIG] No config path set, skipping machine config loading.");
            cached = configs;
            return configs;
        }

        String base = stripTrailingSlash(path);
        String pattern = base + "/*.json";
        Set<String> seenIds = new HashSet<>();

        try {
            Resource[] resources = resourceResolver.getResources(pattern);
            for (Resource resource : resources) {
                try {
                    MachineConfig config;
                    try (InputStream in = resource.getInputStream()) {
                        byte[] bytes = in.readAllBytes();
                        config = objectMapper.readValue(bytes, MachineConfig.class);
                        loadedFiles.put(resource.getFilename(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
                    }

                    List<String> errors = validate(config, seenIds);
                    if (!errors.isEmpty()) {
                        loadingErrors.add(resource.getFilename() + ": " + String.join("; ", errors));
                        log.error("[CONFIG] Rejected {}:", resource.getFilename());
                        for (String error : errors) {
                            log.error("[CONFIG]   - {}", error);
                        }
                        continue;
                    }
                    seenIds.add(config.getMachineId());

                    finalizeConfig(config);
                    configs.add(config);
                    log.info("[CONFIG] Loaded {} (machineId={}, protocol={}), {} elements",
                            resource.getFilename(), config.getMachineId(),
                            config.getProtocol(), config.getMappings().size());
                } catch (Exception e) {
                    loadingErrors.add(resource.getFilename() + ": " + e.getMessage());
                    log.error("[CONFIG] Failed to parse {}: {}", resource.getFilename(), e.getMessage());
                }
            }
        } catch (IOException e) {
            loadingErrors.add("Configuration scan failed: " + e.getMessage());
            log.error("[CONFIG] Failed to scan {}: {}", pattern, e.getMessage());
        }

        cached = configs;
        return configs;
    }

    /** AAS metamodel idShort pattern (IDTA-01001, constraint AASd-002). */
    private static final java.util.regex.Pattern ID_SHORT =
            java.util.regex.Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]{0,127}$");

    private static final Set<String> VALUE_TYPES = Set.of(
            "xs:string", "xs:double", "xs:float", "xs:int", "xs:long",
            "xs:boolean", "xs:dateTime");

    private static final Set<String> CATEGORIES = Set.of(
            "CONSTANT", "PARAMETER", "VARIABLE");

    private List<String> validate(MachineConfig config, Set<String> seenIds) {
        List<String> errors = new ArrayList<>();
        if (config.getMachineId() == null || config.getMachineId().isBlank()) {
            errors.add("missing required field 'machineId'");
        } else if (seenIds.contains(config.getMachineId())) {
            errors.add("duplicate machineId '" + config.getMachineId()
                    + "', already defined by another config file");
        }
        if (config.getProtocol() == null) {
            errors.add("missing required field 'protocol'");
        }
        if (config.getConnection() == null
                || config.getConnection().getEndpoint() == null
                || config.getConnection().getEndpoint().isBlank()) {
            errors.add("missing required field 'connection.endpoint'");
        }
        if (config.getMappings() == null || config.getMappings().isEmpty()) {
            errors.add("'mappings' is missing or empty; a machine must expose at least one element");
        } else {
            Set<String> names = new HashSet<>();
            for (AdapterMapping m : config.getMappings()) {
                if (m.getSourceAddress() == null || m.getSourceAddress().isBlank()) {
                    errors.add("a mapping is missing 'sourceAddress'");
                }
                String idShort = m.getIdShort();
                if (idShort == null || idShort.isBlank()) {
                    errors.add("a mapping is missing 'idShort'");
                } else {
                    if (!ID_SHORT.matcher(idShort).matches()) {
                        errors.add("idShort '" + idShort + "' violates the AAS pattern "
                                + "[a-zA-Z][a-zA-Z0-9_]* (IDTA-01001 AASd-002)");
                    }
                    if (!names.add(idShort)) {
                        errors.add("duplicate idShort '" + idShort + "' within this machine");
                    }
                }
                if (m.getValueType() == null || m.getValueType().isBlank()) {
                    errors.add("mapping '" + idShort + "' is missing 'valueType'");
                } else if (!VALUE_TYPES.contains(m.getValueType())) {
                    errors.add("mapping '" + idShort + "': valueType '" + m.getValueType()
                            + "' is not one of " + VALUE_TYPES);
                }
                if (m.getCategory() != null && !CATEGORIES.contains(m.getCategory())) {
                    errors.add("mapping '" + idShort + "': category '" + m.getCategory()
                            + "' is not one of " + CATEGORIES);
                }
            }
        }
        return errors;
    }

    /**
     * Stamps the machineId onto every mapping, backfills the protocol, and
     * resolves credential placeholders. idShorts are not mutated: they stay
     * exactly as the engineer wrote them, since the routing key
     * {@code machineId.idShort} handles addressing.
     */
    private void finalizeConfig(MachineConfig config) {
        for (AdapterMapping m : config.getMappings()) {
            m.setMachineId(config.getMachineId());
            if (m.getProtocol() == null) {
                m.setProtocol(config.getProtocol());
            }
        }
        resolveSecrets(config.getConnection());
    }

    /**
     * Resolves "${ENV_VAR}" / "${ENV_VAR:default}" placeholders in connection
     * credentials so secrets don't have to live in the JSON file. A placeholder
     * with no matching environment variable and no default resolves to blank
     * (logged), so a missing secret fails visibly at connect time rather than
     * silently using the placeholder text as the password.
     */
    private void resolveSecrets(ConnectionConfig conn) {
        if (conn == null) return;
        conn.setUsername(resolveEnvPlaceholder(conn.getUsername()));
        conn.setPassword(resolveEnvPlaceholder(conn.getPassword()));
    }

    private static String resolveEnvPlaceholder(String value) {
        if (value == null || !value.startsWith("${") || !value.endsWith("}")) return value;
        String inner = value.substring(2, value.length() - 1);
        String name = inner;
        String fallback = null;
        int colon = inner.indexOf(':');
        if (colon >= 0) {
            name = inner.substring(0, colon);
            fallback = inner.substring(colon + 1);
        }
        String resolved = System.getenv(name);
        if (resolved != null) return resolved;
        if (fallback != null) return fallback;
        log.warn("[CONFIG] Environment variable '{}' not set and no default given, using blank", name);
        return "";
    }

    private static String stripTrailingSlash(String path) {
        return (path.endsWith("/") || path.endsWith("\\"))
                ? path.substring(0, path.length() - 1)
                : path;
    }
}
