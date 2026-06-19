package com.opcua.forward.config;

import java.util.ArrayList;
import java.util.List;

public class ForwardRule {

    private String name;
    private MatchCondition match = new MatchCondition();
    private List<ForwardTarget> targets = new ArrayList<>();
    private AlertConfig alerts;
    /** P0 数据质量过滤：默认 dropBad=true，dropUncertain=false */
    private QualityFilter qualityFilter = QualityFilter.dropBadOnly();

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public MatchCondition getMatch() { return match; }
    public void setMatch(MatchCondition v) { this.match = v; }

    public List<ForwardTarget> getTargets() { return targets; }
    public void setTargets(List<ForwardTarget> v) { this.targets = v; }

    public AlertConfig getAlerts() { return alerts; }
    public void setAlerts(AlertConfig alerts) { this.alerts = alerts; }

    public QualityFilter getQualityFilter() { return qualityFilter; }
    public void setQualityFilter(QualityFilter v) {
        this.qualityFilter = v != null ? v : QualityFilter.dropBadOnly();
    }
}
