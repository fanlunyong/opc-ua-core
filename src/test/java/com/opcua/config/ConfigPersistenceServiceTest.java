package com.opcua.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.opcua.forward.config.ForwardRule;
import com.opcua.forward.config.ForwardTarget;
import com.opcua.model.DeviceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigPersistenceServiceTest {

    @TempDir
    Path tempDir;

    private ConfigPersistenceService service;

    @BeforeEach
    void setUp() {
        Path configFile = tempDir.resolve("opcua-runtime.yml");
        service = new ConfigPersistenceService(new ObjectMapper(new YAMLFactory()), configFile);
    }

    @Test
    void shouldSaveAndLoadDevices() throws Exception {
        DeviceConfig config = new DeviceConfig();
        config.setDeviceId("device-1");
        config.setEndpointUrl("opc.tcp://localhost:4840");
        config.setProductId("Test Device");

        service.saveDevices(List.of(config));

        List<DeviceConfig> loaded = service.loadDevices();
        assertThat(loaded).hasSize(1);
        assertThat(loaded.get(0).getDeviceId()).isEqualTo("device-1");
        assertThat(loaded.get(0).getEndpointUrl()).isEqualTo("opc.tcp://localhost:4840");
    }

    @Test
    void shouldReturnEmptyListForMissingFile() throws Exception {
        List<DeviceConfig> loaded = service.loadDevices();
        assertThat(loaded).isEmpty();
    }

    @Test
    void shouldSaveAndLoadRules() throws Exception {
        ForwardRule rule = new ForwardRule();
        rule.setName("test-rule");
        ForwardTarget target = new ForwardTarget();
        target.setType("kafka");
        target.setBootstrapServers("localhost:9092");
        target.setTopic("opcua-data");
        rule.setTargets(List.of(target));

        service.saveRules(List.of(rule));

        List<ForwardRule> loaded = service.loadRules();
        assertThat(loaded).hasSize(1);
        assertThat(loaded.get(0).getName()).isEqualTo("test-rule");
        assertThat(loaded.get(0).getTargets().get(0).getTopic()).isEqualTo("opcua-data");
    }

    @Test
    void shouldPersistAndReloadBothDevicesAndRules() throws Exception {
        DeviceConfig device = new DeviceConfig();
        device.setDeviceId("d1");
        device.setEndpointUrl("opc.tcp://localhost:4840");

        ForwardRule rule = new ForwardRule();
        rule.setName("r1");
        ForwardTarget target = new ForwardTarget();
        target.setType("http");
        target.setUrl("http://localhost:8080");
        rule.setTargets(List.of(target));

        service.saveAll(List.of(device), List.of(rule));

        List<DeviceConfig> loadedDevices = service.loadDevices();
        List<ForwardRule> loadedRules = service.loadRules();
        assertThat(loadedDevices).hasSize(1);
        assertThat(loadedDevices.get(0).getDeviceId()).isEqualTo("d1");
        assertThat(loadedRules).hasSize(1);
        assertThat(loadedRules.get(0).getName()).isEqualTo("r1");
    }
}
