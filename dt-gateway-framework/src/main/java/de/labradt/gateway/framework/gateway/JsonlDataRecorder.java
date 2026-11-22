package de.labradt.gateway.framework.gateway;

import de.labradt.gateway.framework.model.MachineConfigProperties;
import de.labradt.gateway.framework.model.SubmodelElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Appends every {@link SubmodelElement} written to the store as a JSON line
 * in an append-only file. Enabled by setting:
 *
 *   dt.framework.data-log.enabled=true
 *   dt.framework.data-log.path=logs/data.jsonl
 *
 * Each line is a self-contained JSON object, readable by Python (pandas
 * read_json with lines=True), jq, or any text tool. Failures to write are
 * logged but never propagate back to the store.
 */
@Component
@ConditionalOnProperty(prefix = "dt.framework.data-log", name = "enabled", havingValue = "true")
public class JsonlDataRecorder implements SubmodelStoreListener {

    private static final Logger log = LoggerFactory.getLogger(JsonlDataRecorder.class);

    private final Path path;
    private final ReentrantLock writeLock = new ReentrantLock();
    private BufferedWriter writer;
    private volatile boolean closed = false;

    public JsonlDataRecorder(MachineConfigProperties properties) {
        this.path = Paths.get(properties.getDataLog().getPath());
    }

    @PostConstruct
    public void open() throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        writer = Files.newBufferedWriter(path,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
        log.info("Data recorder appending to {}", path.toAbsolutePath());
    }

    @PreDestroy
    public void close() {
        writeLock.lock();
        try {
            closed = true;
            if (writer != null) {
                writer.flush();
                writer.close();
            }
        } catch (IOException e) {
            log.warn("Failed to close data log: {}", e.getMessage());
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void onElementStored(SubmodelElement element) {
        if (closed) return;
        writeLock.lock();
        try {
            if (!closed) {
                writer.write(serialize(element));
                writer.newLine();
                writer.flush();
            }
        } catch (IOException e) {
            log.warn("Failed to record element {}: {}", element.getIdShort(), e.getMessage());
        } finally {
            writeLock.unlock();
        }
    }

    private String serialize(SubmodelElement e) {
        StringBuilder sb = new StringBuilder(160);
        sb.append('{');
        appendField(sb, "recordedAt", Instant.now().toString(), true);
        sb.append(',');
        appendField(sb, "key", e.routingKey(), true);
        sb.append(',');
        appendField(sb, "idShort", e.getIdShort(), true);
        sb.append(',');
        appendField(sb, "value", e.getValue(), true);
        sb.append(',');
        appendField(sb, "valueType", e.getValueType(), true);
        sb.append(',');
        appendField(sb, "unit", e.unit(), true);
        sb.append(',');
        appendField(sb, "timestamp",
                e.timestamp() == null
                        ? null
                        : e.timestamp().atZone(ZoneId.systemDefault()).toInstant().toString(),
                true);
        sb.append(',');
        appendField(sb, "statusCode", e.statusCode(), true);
        sb.append('}');
        return sb.toString();
    }

    private void appendField(StringBuilder sb, String key, String value, boolean quoteValue) {
        sb.append('"').append(key).append("\":");
        if (value == null) {
            sb.append("null");
        } else if (quoteValue) {
            sb.append('"').append(escape(value)).append('"');
        } else {
            sb.append(value);
        }
    }

    private String escape(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
