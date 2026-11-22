package de.labradt.gateway.framework.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.TimeZone;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** ObservedAt / Timestamp are published as UTC instants, whatever zone the Gateway runs in. */
class SubmodelElementTimestampTest {

    @Test
    void qualifiersCarryUtcInstants() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"));
            LocalDateTime local = LocalDateTime.of(2026, 11, 20, 16, 6, 32, 924_722_549);
            SubmodelElement element = new SubmodelElement();
            element.setObservedAt(local);
            Map<String, String> qualifiers = element.getQualifiers().stream()
                    .collect(Collectors.toMap(q -> q.get("type"), q -> q.get("value")));
            assertThat(qualifiers.get("ObservedAt")).isEqualTo("2026-11-20T15:06:32.924722549Z");
            assertThat(Instant.parse(qualifiers.get("ObservedAt"))).isEqualTo(local.atZone(ZoneId.of("Europe/Berlin")).toInstant());
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
