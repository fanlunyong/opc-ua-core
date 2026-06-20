package com.opcua.forward.engine;

import com.opcua.api.OpcUaService;
import com.opcua.config.ConfigChangeEvent;
import com.opcua.forward.config.ForwardProperties;
import com.opcua.forward.config.ForwardRule;
import com.opcua.forward.config.ForwardTarget;
import com.opcua.forward.sender.SenderRegistry;
import com.opcua.model.OpcUaDeviceData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ForwardingEngineConfigIntegrationTest {

    private OpcUaService opcUaService;
    private SenderRegistry senderRegistry;
    private ForwardingEngine engine;

    @BeforeEach
    void setUp() {
        opcUaService = mock(OpcUaService.class);
        senderRegistry = mock(SenderRegistry.class);
        ForwardProperties props = new ForwardProperties();
        // 空规则启动
        engine = new ForwardingEngine(opcUaService, senderRegistry, props);
        engine.init();
    }

    private ForwardRule makeRule(String name, String topic) {
        ForwardRule rule = new ForwardRule();
        rule.setName(name);
        ForwardTarget target = new ForwardTarget();
        target.setType("kafka");
        target.setTopic(topic);
        target.setEnabled(true);
        rule.setTargets(List.of(target));
        return rule;
    }

    private OpcUaDeviceData makeData() {
        OpcUaDeviceData.SourceInfo source = new OpcUaDeviceData.SourceInfo("product-1", "device-1", "opc.tcp://localhost:4840");
        com.opcua.model.OpcUaDataPoint goodPoint = new com.opcua.model.OpcUaDataPoint(
                "ns=2;s=Temp", "Temperature", 25.0, "Double",
                com.opcua.model.Quality.Good, false, "0x00000000",
                java.time.Instant.now(), java.time.Instant.now());
        return new OpcUaDeviceData(java.time.Instant.now(), source, new ArrayList<>(List.of(goodPoint)));
    }

    @Test
    void shouldAddRuleViaConfigChangeEvent() {
        ForwardRule rule = makeRule("test-rule", "test");

        engine.onConfigChange(new ConfigChangeEvent(
                ConfigChangeEvent.ChangeType.RULE_ADDED, "test-rule", rule));

        // 首条规则添加后应注册 listener
        verify(opcUaService).registerListener(engine);
        // 验证规则生效：发送匹配数据应触发 enqueue
        engine.onDataReceived(makeData());
        verify(senderRegistry).getOrCreate(any(ForwardTarget.class));
    }

    @Test
    void shouldRemoveRuleViaConfigChangeEvent() {
        ForwardRule rule = makeRule("test-rule", "test");
        engine.onConfigChange(new ConfigChangeEvent(
                ConfigChangeEvent.ChangeType.RULE_ADDED, "test-rule", rule));

        engine.onConfigChange(new ConfigChangeEvent(
                ConfigChangeEvent.ChangeType.RULE_REMOVED, "test-rule", null));

        // 规则移除后，最后一条规则移除应取消注册 listener
        verify(opcUaService).unregisterListener(engine);
        // 发送数据不应触发 enqueue
        engine.onDataReceived(makeData());
        verify(senderRegistry, never()).getOrCreate(any(ForwardTarget.class));
    }

    @Test
    void shouldUpdateRuleViaConfigChangeEvent() {
        ForwardRule oldRule = makeRule("test-rule", "old-topic");
        engine.onConfigChange(new ConfigChangeEvent(
                ConfigChangeEvent.ChangeType.RULE_ADDED, "test-rule", oldRule));

        ForwardRule newRule = makeRule("test-rule", "new-topic");
        engine.onConfigChange(new ConfigChangeEvent(
                ConfigChangeEvent.ChangeType.RULE_UPDATED, "test-rule", newRule));

        // 验证规则已更新：发送数据应使用新规则（通过 senderRegistry 间接验证）
        engine.onDataReceived(makeData());
        // 应只触发一次 getOrCreate（更新不增加规则数量）
        verify(senderRegistry, times(1)).getOrCreate(any(ForwardTarget.class));
    }

    @Test
    void shouldToggleRuleViaConfigChangeEvent() {
        ForwardRule rule = makeRule("test-rule", "test");
        rule.getTargets().get(0).setEnabled(true);
        engine.onConfigChange(new ConfigChangeEvent(
                ConfigChangeEvent.ChangeType.RULE_ADDED, "test-rule", rule));

        // toggle disable
        ForwardRule disabledRule = makeRule("test-rule", "test");
        disabledRule.getTargets().get(0).setEnabled(false);
        engine.onConfigChange(new ConfigChangeEvent(
                ConfigChangeEvent.ChangeType.RULE_TOGGLED, "test-rule", disabledRule));

        // 发送数据不应触发 enqueue（规则已禁用）
        engine.onDataReceived(makeData());
        verify(senderRegistry, never()).getOrCreate(any(ForwardTarget.class));
    }
}
