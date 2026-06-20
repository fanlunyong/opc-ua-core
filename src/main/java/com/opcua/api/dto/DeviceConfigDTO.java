package com.opcua.api.dto;

import com.opcua.model.DeviceConfig;
import com.opcua.model.DeviceState;
import com.opcua.model.NodeConfig;
import com.opcua.model.SubscriptionGroupConfig;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class DeviceConfigDTO {
    private String id;
    private String name;
    private String endpointUrl;
    private String securityPolicy;
    private Duration timeout;
    private List<NodeDTO> nodes;
    private String status;
    private String lastConnectedAt;

    public static class NodeDTO {
        private String nodeId;
        private String displayName;
        private String dataType;

        public NodeDTO() {}

        public NodeDTO(String nodeId, String displayName, String dataType) {
            this.nodeId = nodeId;
            this.displayName = displayName;
            this.dataType = dataType;
        }

        public String getNodeId() { return nodeId; }
        public void setNodeId(String nodeId) { this.nodeId = nodeId; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        public String getDataType() { return dataType; }
        public void setDataType(String dataType) { this.dataType = dataType; }
    }

    public static DeviceConfig toDeviceConfig(DeviceConfigDTO dto) {
        DeviceConfig config = new DeviceConfig();
        config.setDeviceId(dto.getId());
        config.setProductId(dto.getName());
        config.setEndpointUrl(dto.getEndpointUrl());
        if (dto.getTimeout() != null) {
            config.setSessionTimeout((int) dto.getTimeout().getSeconds());
        }
        if (dto.getSecurityPolicy() != null) {
            config.getSecurity().setPolicy(dto.getSecurityPolicy());
        }
        if (dto.getNodes() != null && !dto.getNodes().isEmpty()) {
            SubscriptionGroupConfig sg = new SubscriptionGroupConfig();
            sg.setGroupName("default");
            List<NodeConfig> nodeConfigs = new ArrayList<>();
            for (NodeDTO nd : dto.getNodes()) {
                NodeConfig nc = new NodeConfig();
                nc.setNodeId(nd.getNodeId());
                nc.setDisplayName(nd.getDisplayName());
                nc.setDataType(nd.getDataType());
                nodeConfigs.add(nc);
            }
            sg.setNodes(nodeConfigs);
            config.setSubscriptions(List.of(sg));
        }
        return config;
    }

    public static DeviceConfigDTO fromDeviceConfig(DeviceConfig config, DeviceState state) {
        DeviceConfigDTO dto = new DeviceConfigDTO();
        dto.setId(config.getDeviceId());
        dto.setName(config.getProductId());
        dto.setEndpointUrl(config.getEndpointUrl());
        dto.setSecurityPolicy(config.getSecurity().getPolicy());
        dto.setTimeout(Duration.ofSeconds(config.getSessionTimeout()));
        if (config.getSubscriptions() != null) {
            List<NodeDTO> nodes = new ArrayList<>();
            for (SubscriptionGroupConfig sg : config.getSubscriptions()) {
                if (sg.getNodes() != null) {
                    for (NodeConfig nc : sg.getNodes()) {
                        nodes.add(new NodeDTO(nc.getNodeId(), nc.getDisplayName(), nc.getDataType()));
                    }
                }
            }
            dto.setNodes(nodes);
        }
        if (state != null) {
            dto.setStatus(state.getState().name());
            if (state.getConnectedSince() != null) {
                dto.setLastConnectedAt(state.getConnectedSince().toString());
            }
        }
        return dto;
    }

    // --- Getters / Setters ---

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEndpointUrl() { return endpointUrl; }
    public void setEndpointUrl(String endpointUrl) { this.endpointUrl = endpointUrl; }
    public String getSecurityPolicy() { return securityPolicy; }
    public void setSecurityPolicy(String securityPolicy) { this.securityPolicy = securityPolicy; }
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration timeout) { this.timeout = timeout; }
    public List<NodeDTO> getNodes() { return nodes; }
    public void setNodes(List<NodeDTO> nodes) { this.nodes = nodes; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getLastConnectedAt() { return lastConnectedAt; }
    public void setLastConnectedAt(String lastConnectedAt) { this.lastConnectedAt = lastConnectedAt; }
}
