package com.opcua.forward.engine;

import com.opcua.api.OpcUaService;
import com.opcua.forward.config.AlertConfig;
import com.opcua.forward.config.ForwardProperties;
import com.opcua.forward.config.ForwardRule;
import com.opcua.forward.config.ForwardTarget;
import com.opcua.forward.config.QualityFilter;
import com.opcua.forward.sender.Sender;
import com.opcua.forward.sender.SenderRegistry;
import com.opcua.model.Quality;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ForwardingEngineAlertRoutingTest {

    @Test
    void goodOnly_routesToMainTargetsOnly() {
        Result r = run(Quality.Good, true);
        verify(r.mainSender).enqueue(any());
        verifyNoInteractions(r.alertSender);
    }

    @Test
    void badData_routesToMainAndAlertTargets() {
        Result r = run(Quality.Bad, true);
        // 主通道：dropBadOnly 默认 → 全 Bad 被过滤为 null → 主通道不发
        verifyNoInteractions(r.mainSender);
        // alert 通道：Bad 触发，发原始 data
        verify(r.alertSender).enqueue(any());
    }

    @Test
    void uncertainData_routesToMainAndAlertTargets() {
        Result r = run(Quality.Uncertain, true);
        // dropBadOnly 不丢 Uncertain → 主通道发送
        verify(r.mainSender).enqueue(any());
        // Uncertain 触发 alert
        verify(r.alertSender).enqueue(any());
    }

    @Test
    void alertsDisabled_doesNotRouteToAlertTargets() {
        Result r = run(Quality.Bad, false);
        // alerts disabled
        verifyNoInteractions(r.alertSender);
    }

    private Result run(Quality q, boolean alertsEnabled) {
        SenderRegistry registry = mock(SenderRegistry.class);
        Sender main = mock(Sender.class);
        Sender alert = mock(Sender.class);

        ForwardTarget mainT = ForwardingEngineRoutingTest.kafka("k:9092", "main");
        ForwardTarget alertT = ForwardingEngineRoutingTest.kafka("k:9092", "alert");

        when(registry.getOrCreate(mainT)).thenReturn(main);
        when(registry.getOrCreate(alertT)).thenReturn(alert);

        ForwardRule rule = new ForwardRule();
        rule.setName("r1");
        rule.setTargets(List.of(mainT));
        AlertConfig alerts = new AlertConfig();
        alerts.setEnabled(alertsEnabled);
        alerts.setTargets(List.of(alertT));
        rule.setAlerts(alerts);

        ForwardProperties props = new ForwardProperties();
        props.setRules(new ArrayList<>(List.of(rule)));

        ForwardingEngine engine = new ForwardingEngine(
                mock(OpcUaService.class), registry, props);

        engine.onDataReceived(ForwardingEngineRoutingTest.makeData("p", "d", q));

        return new Result(main, alert);
    }

    private record Result(Sender mainSender, Sender alertSender) { }
}