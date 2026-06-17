package com.opcua.config;

import com.opcua.model.DeviceConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * OPC UA 配置属性绑定类。
 *
 * <p>绑定前缀 {@code opcua}，支持 YAML 配置中的设备列表解析。</p>
 */
@Component
@ConfigurationProperties(prefix = "opcua")
public class OpcUaProperties {

    /** 是否启用 OPC UA 采集 */
    private boolean enabled = true;

    /** 数据分发配置 */
    private DispatchConfig dispatch = new DispatchConfig();

    /** 设备配置列表 */
    private List<DeviceConfig> devices = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public DispatchConfig getDispatch() {
        return dispatch;
    }

    public void setDispatch(DispatchConfig dispatch) {
        this.dispatch = dispatch;
    }

    public List<DeviceConfig> getDevices() {
        return devices;
    }

    public void setDevices(List<DeviceConfig> devices) {
        this.devices = devices;
    }

    /**
     * 数据分发线程池配置。
     */
    public static class DispatchConfig {

        /** 线程池大小，默认 4 */
        private int threadPoolSize = 4;

        /** 分桶数量，默认 8 */
        private int bucketCount = 8;

        /** 队列容量，默认 1024 */
        private int queueCapacity = 1024;

        public int getThreadPoolSize() {
            return threadPoolSize;
        }

        public void setThreadPoolSize(int threadPoolSize) {
            this.threadPoolSize = threadPoolSize;
        }

        public int getBucketCount() {
            return bucketCount;
        }

        public void setBucketCount(int bucketCount) {
            this.bucketCount = bucketCount;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }
    }
}
