package com.opcua.api.dto;

import com.opcua.model.DeviceConfig;
import com.opcua.model.NodeConfig;
import com.opcua.model.SubscriptionGroupConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DeviceConfigDTOTest {

    @Test
    void shouldConvertDtoToDeviceConfig() {
        DeviceConfigDTO dto = new DeviceConfigDTO();
        dto.setId("device-1");
        dto.setName("Test Device");
        dto.setEndpointUrl("opc.tcp://localhost:4840");
        dto.setSecurityPolicy("Basic256Sha256");
        dto.setTimeout(java.time.Duration.ofSeconds(30));
        dto.setNodes(List.of(
                new DeviceConfigDTO.NodeDTO("ns=2;s=Temp", "Temperature", "Double"),
                new DeviceConfigDTO.NodeDTO("ns=2;s=Pressure", "Pressure", "Double")
        ));

        DeviceConfig config = DeviceConfigDTO.toDeviceConfig(dto);

        assertThat(config.getDeviceId()).isEqualTo("device-1");
        assertThat(config.getProductId()).isEqualTo("Test Device");
        assertThat(config.getEndpointUrl()).isEqualTo("opc.tcp://localhost:4840");
        assertThat(config.getSecurity().getPolicy()).isEqualTo("Basic256Sha256");
        assertThat(config.getSessionTimeout()).isEqualTo(30);
        assertThat(config.getSubscriptions()).hasSize(1);
        assertThat(config.getSubscriptions().get(0).getNodes()).hasSize(2);
        assertThat(config.getSubscriptions().get(0).getNodes().get(0).getNodeId())
                .isEqualTo("ns=2;s=Temp");
    }

    @Test
    void shouldConvertDeviceConfigToDto() {
        DeviceConfig config = new DeviceConfig();
        config.setDeviceId("device-1");
        config.setProductId("Test Device");
        config.setEndpointUrl("opc.tcp://localhost:4840");
        config.setSessionTimeout(30);
        config.getSecurity().setPolicy("Basic256Sha256");
        SubscriptionGroupConfig sg = new SubscriptionGroupConfig();
        sg.setGroupName("default");
        NodeConfig nc = new NodeConfig();
        nc.setNodeId("ns=2;s=Temp");
        nc.setDisplayName("Temperature");
        sg.setNodes(List.of(nc));
        config.setSubscriptions(List.of(sg));

        DeviceConfigDTO dto = DeviceConfigDTO.fromDeviceConfig(config, null);

        assertThat(dto.getId()).isEqualTo("device-1");
        assertThat(dto.getName()).isEqualTo("Test Device");
        assertThat(dto.getEndpointUrl()).isEqualTo("opc.tcp://localhost:4840");
        assertThat(dto.getNodes()).hasSize(1);
        assertThat(dto.getNodes().get(0).getNodeId()).isEqualTo("ns=2;s=Temp");
    }

    @Test
    void shouldHandleNullNodesGracefully() {
        DeviceConfigDTO dto = new DeviceConfigDTO();
        dto.setId("device-1");
        dto.setEndpointUrl("opc.tcp://localhost:4840");

        DeviceConfig config = DeviceConfigDTO.toDeviceConfig(dto);

        assertThat(config.getSubscriptions()).isEmpty();
    }
}
