package com.opcua.model;

import java.time.Instant;

/**
 * OPC UA 数据点 — 单次采集的节点数据。
 */
public class OpcUaDataPoint {

    private final String nodeId;
    private final String displayName;
    private final Object value;
    private final String dataType;
    private final Quality quality;
    private final String statusCode;
    private final Instant sourceTimestamp;
    private final Instant serverTimestamp;

    /**
     * 全参构造函数。
     */
    public OpcUaDataPoint(String nodeId,
                          String displayName,
                          Object value,
                          String dataType,
                          Quality quality,
                          String statusCode,
                          Instant sourceTimestamp,
                          Instant serverTimestamp) {
        this.nodeId = nodeId;
        this.displayName = displayName;
        this.value = value;
        this.dataType = dataType;
        this.quality = quality;
        this.statusCode = statusCode;
        this.sourceTimestamp = sourceTimestamp;
        this.serverTimestamp = serverTimestamp;
    }

    // --- Getters ---

    public String getNodeId() {
        return nodeId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Object getValue() {
        return value;
    }

    public String getDataType() {
        return dataType;
    }

    public Quality getQuality() {
        return quality;
    }

    public String getStatusCode() {
        return statusCode;
    }

    public Instant getSourceTimestamp() {
        return sourceTimestamp;
    }

    public Instant getServerTimestamp() {
        return serverTimestamp;
    }

    @Override
    public String toString() {
        return "OpcUaDataPoint{" +
                "nodeId='" + nodeId + '\'' +
                ", displayName='" + displayName + '\'' +
                ", value=" + value +
                ", quality=" + quality +
                '}';
    }
}
