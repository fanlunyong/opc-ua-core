package com.opcua.forward.engine;

import com.opcua.api.OpcUaDataListener;
import com.opcua.api.OpcUaService;
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

/**
 * 转发引擎主入口。注册为 OpcUaDataListener，在 DataDispatchEngine bucket 线程上同步
 * 执行规则匹配，匹配后调用 sender.enqueue（异步发送）。
 */
public class ForwardingEngine implements OpcUaDataListener {

    private static final Logger logger = LoggerFactory.getLogger(ForwardingEngine.class);

    private final OpcUaService opcUaService;
    private final SenderRegistry senderRegistry;
    private final ForwardProperties properties;

    public ForwardingEngine(OpcUaService opcUaService,
                            SenderRegistry senderRegistry,
                            ForwardProperties properties) {
        this.opcUaService = opcUaService;
        this.senderRegistry = senderRegistry;
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        if (properties.getRules() == null || properties.getRules().isEmpty()) {
            logger.info("opcua.forward.rules is empty, skipping listener registration");
            return;
        }
        opcUaService.registerListener(this);
        logger.info("ForwardingEngine registered with {} rules", properties.getRules().size());
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
        List<ForwardRule> rules = properties.getRules();
        if (rules == null) return;
        for (ForwardRule rule : rules) {
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
}