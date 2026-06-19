package com.opcua.forward.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 转发模块根配置。绑定到 opcua.forward.*。
 */
@ConfigurationProperties(prefix = "opcua.forward")
public class ForwardProperties {

    private boolean enabled = true;
    private Duration shutdownTimeout = Duration.ofSeconds(5);
    private List<ForwardRule> rules = new ArrayList<>();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Duration getShutdownTimeout() { return shutdownTimeout; }
    public void setShutdownTimeout(Duration v) { this.shutdownTimeout = v; }

    public List<ForwardRule> getRules() { return rules; }
    public void setRules(List<ForwardRule> v) { this.rules = v; }
}
