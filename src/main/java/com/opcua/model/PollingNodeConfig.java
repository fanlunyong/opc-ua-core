package com.opcua.model;

/**
 * 轮询节点配置。
 */
public class PollingNodeConfig {

    /** 节点标识符 */
    private String nodeId;

    /** 显示名称 */
    private String displayName;

    /** 数据类型（可选） */
    private String dataType;

    /** 轮询间隔（毫秒），默认 5000 */
    private int interval = 5000;

    /** 是否启用质量检查，默认 false */
    private boolean qualityCheck = false;

    public PollingNodeConfig() {
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

    public int getInterval() {
        return interval;
    }

    public void setInterval(int interval) {
        this.interval = interval;
    }

    public boolean isQualityCheck() {
        return qualityCheck;
    }

    public void setQualityCheck(boolean qualityCheck) {
        this.qualityCheck = qualityCheck;
    }
}
