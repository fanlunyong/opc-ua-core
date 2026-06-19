package com.opcua.forward.config;

/**
 * 转发规则匹配条件。所有字段为可选；未配置视为通配。
 */
public class MatchCondition {

    private String productId;
    private String deviceId;
    private String nodeId;

    public String getProductId() { return productId; }
    public void setProductId(String productId) { this.productId = productId; }

    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }

    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }
}
