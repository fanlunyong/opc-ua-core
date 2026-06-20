package com.opcua.forward.engine;

import com.opcua.api.OpcUaDataListener;
import com.opcua.api.OpcUaService;
import com.opcua.config.ConfigChangeEvent;
import com.opcua.config.ConfigChangeListener;
import com.opcua.forward.config.AlertConfig;
import com.opcua.forward.config.ForwardProperties;
import com.opcua.forward.config.ForwardRule;
import com.opcua.forward.config.ForwardTarget;
import com.opcua.forward.sender.SenderRegistry;
import com.opcua.model.OpcUaDeviceData;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 转发引擎主入口。注册为 OpcUaDataListener，在 DataDispatchEngine bucket 线程上同步
 * 执行规则匹配，匹配后调用 sender.enqueue（异步发送）。
 *
 * <p>实现 {@link ConfigChangeListener}，支持运行时通过 ConfigChangeEvent 增删改转发规则。</p>
 */
public class ForwardingEngine implements OpcUaDataListener, ConfigChangeListener {

    private static final Logger logger = LoggerFactory.getLogger(ForwardingEngine.class);

    private final OpcUaService opcUaService;
    private final SenderRegistry senderRegistry;
    private final ForwardProperties properties;

    /** 运行时可变的规则列表，线程安全 */
    private final List<ForwardRule> dynamicRules = new CopyOnWriteArrayList<>();

    public ForwardingEngine(OpcUaService opcUaService,
                            SenderRegistry senderRegistry,
                            ForwardProperties properties) {
        this.opcUaService = opcUaService;
        this.senderRegistry = senderRegistry;
        this.properties = properties;
        if (properties.getRules() != null) {
            dynamicRules.addAll(properties.getRules());
        }
    }

    @PostConstruct
    public void init() {
        if (!dynamicRules.isEmpty()) {
            opcUaService.registerListener(this);
            logger.info("ForwardingEngine registered with {} rules", dynamicRules.size());
        } else {
            logger.info("No rules configured, ForwardingEngine listener not registered");
        }
    }

    @PreDestroy
    public void shutdown() {
        try {
            opcUaService.unregisterListener(this);
        } catch (Exception e) {
            logger.warn("unregisterListener error: {}", e.getMessage());
        }
        senderRegistry.shutdown(properties.getShutdownTimeout());
    }

    @Override
    public void onDataReceived(OpcUaDeviceData data) {
        if (dynamicRules.isEmpty()) return;
        for (ForwardRule rule : dynamicRules) {
            if (!RuleMatcher.matches(rule.getMatch(), data)) continue;

            // P0 数据质量过滤（仅作用于主 targets；alert 通道保持原始数据以便告警上下文完整）
            OpcUaDeviceData filtered = QualityFilterApplier.apply(data, rule.getQualityFilter());

            // 主 targets — 仅当存在 Good/被允许的数据点时 enqueue
            if (filtered != null && rule.getTargets() != null) {
                for (ForwardTarget t : rule.getTargets()) {
                    if (!t.isEnabled()) continue;
                    try {
                        senderRegistry.getOrCreate(t).enqueue(filtered);
                    } catch (Exception e) {
                        logger.warn("Forward to target {}/{} error: {}",
                                t.getType(), t.getTopic(), e.getMessage());
                    }
                }
            }

            // alerts.targets（Bad/Uncertain 触发）— 使用原始未过滤数据，保留 Bad/Uncertain 上下文
            AlertConfig alerts = rule.getAlerts();
            if (alerts != null && alerts.isEnabled() && AlertDetector.hasBadOrUncertain(data)) {
                if (alerts.getTargets() != null) {
                    for (ForwardTarget at : alerts.getTargets()) {
                        if (!at.isEnabled()) continue;
                        try {
                            senderRegistry.getOrCreate(at).enqueue(data);
                        } catch (Exception e) {
                            logger.warn("Alert forward error: {}", e.getMessage());
                        }
                    }
                }
            }
        }
    }

    @Override
    public void onConfigChange(ConfigChangeEvent event) {
        switch (event.getType()) {
            case RULE_ADDED -> {
                ForwardRule rule = (ForwardRule) event.getPayload();
                boolean exists = dynamicRules.stream()
                        .anyMatch(r -> r.getName().equals(event.getTargetId()));
                if (!exists) {
                    dynamicRules.add(rule);
                    if (dynamicRules.size() == 1) {
                        opcUaService.registerListener(this);
                    }
                }
                logger.info("Rule added via config change: {} (exists={})", event.getTargetId(), exists);
            }
            case RULE_REMOVED -> {
                dynamicRules.removeIf(r -> r.getName().equals(event.getTargetId()));
                if (dynamicRules.isEmpty()) {
                    opcUaService.unregisterListener(this);
                }
                logger.info("Rule removed via config change: {}", event.getTargetId());
            }
            case RULE_UPDATED -> {
                ForwardRule updated = (ForwardRule) event.getPayload();
                for (int i = 0; i < dynamicRules.size(); i++) {
                    if (dynamicRules.get(i).getName().equals(event.getTargetId())) {
                        dynamicRules.set(i, updated);
                        break;
                    }
                }
                logger.info("Rule updated via config change: {}", event.getTargetId());
            }
            case RULE_TOGGLED -> {
                ForwardRule toggled = (ForwardRule) event.getPayload();
                for (int i = 0; i < dynamicRules.size(); i++) {
                    if (dynamicRules.get(i).getName().equals(event.getTargetId())) {
                        dynamicRules.set(i, toggled);
                        break;
                    }
                }
                logger.info("Rule toggled via config change: {}", event.getTargetId());
            }
        }
    }
}
