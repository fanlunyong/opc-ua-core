package com.opcua.api.dto;

import com.opcua.forward.config.ForwardRule;
import com.opcua.forward.config.ForwardTarget;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ForwardRuleDTO {
    private String name;
    private String type;
    private boolean enabled;
    private Map<String, Object> config;

    public static ForwardRule toForwardRule(ForwardRuleDTO dto) {
        ForwardRule rule = new ForwardRule();
        rule.setName(dto.getName());
        ForwardTarget target = new ForwardTarget();
        target.setType(dto.getType() != null ? dto.getType().toLowerCase() : null);
        target.setEnabled(dto.isEnabled());
        if (dto.getConfig() != null) {
            applyConfig(target, dto.getConfig());
            if (dto.getConfig().containsKey("deviceId")) {
                rule.getMatch().setDeviceId((String) dto.getConfig().get("deviceId"));
            }
            if (dto.getConfig().containsKey("productId")) {
                rule.getMatch().setProductId((String) dto.getConfig().get("productId"));
            }
            if (dto.getConfig().containsKey("nodeId")) {
                rule.getMatch().setNodeId((String) dto.getConfig().get("nodeId"));
            }
        }
        rule.setTargets(List.of(target));
        return rule;
    }

    public static ForwardRuleDTO fromForwardRule(ForwardRule rule) {
        ForwardRuleDTO dto = new ForwardRuleDTO();
        dto.setName(rule.getName());
        if (rule.getTargets() != null && !rule.getTargets().isEmpty()) {
            ForwardTarget t = rule.getTargets().get(0);
            dto.setType(t.getType() != null ? t.getType().toUpperCase() : null);
            dto.setEnabled(t.isEnabled());
            dto.setConfig(extractConfig(t, rule));
        }
        return dto;
    }

    private static void applyConfig(ForwardTarget target, Map<String, Object> cfg) {
        if (cfg.containsKey("bootstrapServers")) target.setBootstrapServers((String) cfg.get("bootstrapServers"));
        if (cfg.containsKey("securityProtocol")) target.setSecurityProtocol((String) cfg.get("securityProtocol"));
        if (cfg.containsKey("topic")) target.setTopic((String) cfg.get("topic"));
        if (cfg.containsKey("url")) target.setUrl((String) cfg.get("url"));
        if (cfg.containsKey("bucket")) target.setBucket((String) cfg.get("bucket"));
        if (cfg.containsKey("org")) target.setOrg((String) cfg.get("org"));
        if (cfg.containsKey("token")) target.setToken((String) cfg.get("token"));
        if (cfg.containsKey("brokerUrl")) target.setBrokerUrl((String) cfg.get("brokerUrl"));
        if (cfg.containsKey("clientId")) target.setClientId((String) cfg.get("clientId"));
        if (cfg.containsKey("qos") && cfg.get("qos") instanceof Number)
            target.setQos(((Number) cfg.get("qos")).intValue());
        if (cfg.containsKey("timeoutMillis") && cfg.get("timeoutMillis") instanceof Number)
            target.setTimeoutMillis(((Number) cfg.get("timeoutMillis")).longValue());
        if (cfg.containsKey("queueCapacity") && cfg.get("queueCapacity") instanceof Number)
            target.setQueueCapacity(((Number) cfg.get("queueCapacity")).intValue());
    }

    private static Map<String, Object> extractConfig(ForwardTarget t, ForwardRule rule) {
        Map<String, Object> cfg = new LinkedHashMap<>();
        if (t.getBootstrapServers() != null) cfg.put("bootstrapServers", t.getBootstrapServers());
        if (t.getSecurityProtocol() != null) cfg.put("securityProtocol", t.getSecurityProtocol());
        if (t.getTopic() != null) cfg.put("topic", t.getTopic());
        if (t.getUrl() != null) cfg.put("url", t.getUrl());
        if (t.getBucket() != null) cfg.put("bucket", t.getBucket());
        if (t.getOrg() != null) cfg.put("org", t.getOrg());
        if (t.getToken() != null) cfg.put("token", t.getToken());
        if (t.getBrokerUrl() != null) cfg.put("brokerUrl", t.getBrokerUrl());
        if (t.getClientId() != null) cfg.put("clientId", t.getClientId());
        cfg.put("qos", t.getQos());
        cfg.put("timeoutMillis", t.getTimeoutMillis());
        cfg.put("queueCapacity", t.getQueueCapacity());
        if (rule.getMatch().getDeviceId() != null) cfg.put("deviceId", rule.getMatch().getDeviceId());
        if (rule.getMatch().getProductId() != null) cfg.put("productId", rule.getMatch().getProductId());
        if (rule.getMatch().getNodeId() != null) cfg.put("nodeId", rule.getMatch().getNodeId());
        return cfg;
    }

    // --- Getters / Setters ---

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Map<String, Object> getConfig() { return config; }
    public void setConfig(Map<String, Object> config) { this.config = config; }
}
