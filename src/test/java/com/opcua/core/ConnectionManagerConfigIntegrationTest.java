package com.opcua.core;

import com.opcua.model.DeviceConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionManagerConfigIntegrationTest {

    @Test
    void shouldAddAndRemoveDevice() {
        ConnectionManager cm = new ConnectionManager();

        DeviceConfig config = new DeviceConfig();
        config.setDeviceId("test-device");
        config.setEndpointUrl("opc.tcp://localhost:4840");

        cm.addDevice(config);
        assertThat(cm.getDeviceCount()).isEqualTo(1);
        assertThat(cm.getState("test-device")).isNotNull();

        cm.removeDevice("test-device");
        assertThat(cm.getDeviceCount()).isEqualTo(0);
        assertThat(cm.getState("test-device")).isNull();
    }

    @Test
    void shouldUpdateDevice() {
        ConnectionManager cm = new ConnectionManager();

        DeviceConfig oldConfig = new DeviceConfig();
        oldConfig.setDeviceId("update-device");
        oldConfig.setEndpointUrl("opc.tcp://localhost:4840");
        oldConfig.setMaxConnections(2);

        cm.addDevice(oldConfig);
        assertThat(cm.getDeviceCount()).isEqualTo(1);

        DeviceConfig newConfig = new DeviceConfig();
        newConfig.setDeviceId("update-device");
        newConfig.setEndpointUrl("opc.tcp://localhost:4841");
        newConfig.setMaxConnections(3);

        cm.updateDevice("update-device", newConfig);
        assertThat(cm.getDeviceCount()).isEqualTo(1);
        assertThat(cm.getState("update-device")).isNotNull();
    }

    @Test
    void shouldReturnDeviceCount() {
        ConnectionManager cm = new ConnectionManager();
        assertThat(cm.getDeviceCount()).isEqualTo(0);

        DeviceConfig c1 = new DeviceConfig();
        c1.setDeviceId("d1");
        c1.setEndpointUrl("opc.tcp://localhost:4840");
        cm.addDevice(c1);

        DeviceConfig c2 = new DeviceConfig();
        c2.setDeviceId("d2");
        c2.setEndpointUrl("opc.tcp://localhost:4841");
        cm.addDevice(c2);

        assertThat(cm.getDeviceCount()).isEqualTo(2);
    }
}
