package com.opcua.config;

public class ConfigChangeEvent {

    public enum ChangeType {
        DEVICE_ADDED,
        DEVICE_REMOVED,
        DEVICE_UPDATED,
        RULE_ADDED,
        RULE_REMOVED,
        RULE_UPDATED,
        RULE_TOGGLED
    }

    private final ChangeType type;
    private final String targetId;
    private final Object payload;

    public ConfigChangeEvent(ChangeType type, String targetId, Object payload) {
        this.type = type;
        this.targetId = targetId;
        this.payload = payload;
    }

    public ChangeType getType() { return type; }
    public String getTargetId() { return targetId; }
    public Object getPayload() { return payload; }
}
