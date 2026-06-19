package com.opcua.forward.config;

import java.util.ArrayList;
import java.util.List;

public class AlertConfig {

    private boolean enabled;
    private List<ForwardTarget> targets = new ArrayList<>();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public List<ForwardTarget> getTargets() { return targets; }
    public void setTargets(List<ForwardTarget> v) { this.targets = v; }
}
