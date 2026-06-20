package com.opcua.config;

@FunctionalInterface
public interface ConfigChangeListener {
    void onConfigChange(ConfigChangeEvent event);
}
