package com.opcua.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 订阅组配置。
 */
public class SubscriptionGroupConfig {

    /** 订阅组名称 */
    private String name;

    /** 采样间隔（毫秒），默认 1000 */
    private int intervalMs = 1000;

    /** 订阅节点列表 */
    private List<NodeConfig> nodes = new ArrayList<>();

    public SubscriptionGroupConfig() {
    }

    // --- Getters / Setters ---

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getIntervalMs() {
        return intervalMs;
    }

    public void setIntervalMs(int intervalMs) {
        this.intervalMs = intervalMs;
    }

    public List<NodeConfig> getNodes() {
        return nodes;
    }

    public void setNodes(List<NodeConfig> nodes) {
        this.nodes = nodes;
    }
}
