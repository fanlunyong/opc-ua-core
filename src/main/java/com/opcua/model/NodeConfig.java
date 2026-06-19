package com.opcua.model;

/**
 * OPC UA 节点配置（用于订阅）。
 */
public class NodeConfig {

    /** 节点标识符 */
    private String nodeId;

    /** 显示名称 */
    private String displayName;

    /** 数据类型（可选） */
    private String dataType;

    /** 是否启用质量检查，默认 false */
    private boolean qualityCheck = false;

    public NodeConfig() {
    }

    // --- Getters / Setters ---

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getDataType() {
        return dataType;
    }

    public void setDataType(String dataType) {
        this.dataType = dataType;
    }

    public boolean isQualityCheck() {
        return qualityCheck;
    }

    public void setQualityCheck(boolean qualityCheck) {
        this.qualityCheck = qualityCheck;
    }
}
