package com.opcua.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 订阅组配置。
 */
public class SubscriptionGroupConfig {

    /** 订阅组名称 */
    private String groupName;

    /** 采样间隔（毫秒），默认 1000 */
    private int samplingInterval = 1000;

    /** 订阅节点列表 */
    private List<NodeConfig> nodes = new ArrayList<>();

    public SubscriptionGroupConfig() {
    }

    // --- Getters / Setters ---

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    public int getSamplingInterval() {
        return samplingInterval;
    }

    public void setSamplingInterval(int samplingInterval) {
        this.samplingInterval = samplingInterval;
    }

    public List<NodeConfig> getNodes() {
        return nodes;
    }

    public void setNodes(List<NodeConfig> nodes) {
        this.nodes = nodes;
    }
}
