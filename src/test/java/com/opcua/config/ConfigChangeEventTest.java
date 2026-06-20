package com.opcua.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigChangeEventTest {

    @Test
    void shouldCreateDeviceAddedEvent() {
        ConfigChangeEvent event = new ConfigChangeEvent(
                ConfigChangeEvent.ChangeType.DEVICE_ADDED, "device-1", "payload");

        assertThat(event.getType()).isEqualTo(ConfigChangeEvent.ChangeType.DEVICE_ADDED);
        assertThat(event.getTargetId()).isEqualTo("device-1");
        assertThat(event.getPayload()).isEqualTo("payload");
    }

    @Test
    void shouldCoverAllChangeTypes() {
        for (ConfigChangeEvent.ChangeType type : ConfigChangeEvent.ChangeType.values()) {
            ConfigChangeEvent event = new ConfigChangeEvent(type, "target", null);
            assertThat(event.getType()).isEqualTo(type);
        }
    }
}
