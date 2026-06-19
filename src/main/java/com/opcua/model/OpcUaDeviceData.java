package com.opcua.model;

import java.time.Instant;
import java.util.List;

/**
 * OPC UA 设备级数据 — 一个时间窗口内同一设备所有数据点的聚合。
 */
public class OpcUaDeviceData {

    private final Instant timestamp;
    private final SourceInfo source;
    private final List<OpcUaDataPoint> data;

    /**
     * 全参构造函数。
     */
    public OpcUaDeviceData(Instant timestamp, SourceInfo source, List<OpcUaDataPoint> data) {
        this.timestamp = timestamp;
        this.source = source;
        this.data = data;
    }

    // --- Getters ---

    public Instant getTimestamp() {
        return timestamp;
    }

    public SourceInfo getSource() {
        return source;
    }

    public List<OpcUaDataPoint> getData() {
        return data;
    }

    /**
     * 数据源信息。
     */
    public static class SourceInfo {

        private final String productId;
        private final String deviceId;
        private final String endpointUrl;

        public SourceInfo(String productId, String deviceId, String endpointUrl) {
            this.productId = productId;
            this.deviceId = deviceId;
            this.endpointUrl = endpointUrl;
        }

        public String getProductId() {
            return productId;
        }

        public String getDeviceId() {
            return deviceId;
        }

        public String getEndpointUrl() {
            return endpointUrl;
        }

        @Override
        public String toString() {
            return "SourceInfo{" +
                    "productId='" + productId + '\'' +
                    ", deviceId='" + deviceId + '\'' +
                    ", endpointUrl='" + endpointUrl + '\'' +
                    '}';
        }
    }

    @Override
    public String toString() {
        return "OpcUaDeviceData{" +
                "timestamp=" + timestamp +
                ", source=" + source +
                ", dataSize=" + (data != null ? data.size() : 0) +
                '}';
    }
}
