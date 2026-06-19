package com.opcua.forward.engine;

import com.opcua.forward.config.AlertConfig;
import com.opcua.forward.config.ForwardProperties;
import com.opcua.forward.config.ForwardRule;
import com.opcua.forward.config.ForwardTarget;
import com.opcua.forward.sender.Sender;
import com.opcua.forward.sender.SenderRegistry;
import com.opcua.api.OpcUaService;
import com.opcua.model.OpcUaDataPoint;
import com.opcua.model.OpcUaDeviceData;
import com.opcua.model.Quality;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class ForwardingEngineRoutingTest {

    @Test
    void onDataReceived_routesToAllEnabledTargetsOfMatchingRule() {
        SenderRegistry registry = mock(SenderRegistry.class);
        Sender s1 = mock(Sender.class);
        Sender s2 = mock(Sender.class);
        Sender s3 = mock(Sender.class);
        when(registry.getOrCreate(any())).thenReturn(s1, s2, s3);

        ForwardTarget t1 = kafka("k:9092", "topic-1"); t1.setEnabled(true);
        ForwardTarget t2 = kafka("k:9092", "topic-2"); t2.setEnabled(true);
        ForwardTarget t3 = kafka("k:9092", "topic-3"); t3.setEnabled(false);

        ForwardRule rule = new ForwardRule();
        rule.setName("r1");
        rule.setTargets(List.of(t1, t2, t3));

        ForwardProperties props = new ForwardProperties();
        props.setShutdownTimeout(Duration.ofSeconds(1));
        props.setRules(new ArrayList<>(List.of(rule)));

        ForwardingEngine engine = new ForwardingEngine(
                mock(OpcUaService.class), registry, props);

        OpcUaDeviceData data = makeData("line-A", "d1", Quality.Good);
        engine.onDataReceived(data);

        // disabled t3 不被路由
        verify(registry, times(2)).getOrCreate(any());
        verify(s1).enqueue(data);
        verify(s2).enqueue(data);
        verifyNoInteractions(s3);
    }

    @Test
    void onDataReceived_skipsRulesWithoutMatch() {
        SenderRegistry registry = mock(SenderRegistry.class);
        Sender s = mock(Sender.class);
        when(registry.getOrCreate(any())).thenReturn(s);

        ForwardRule r1 = ruleWithProductId("line-A", kafka("k:9092", "A"));
        ForwardRule r2 = ruleWithProductId("line-B", kafka("k:9092", "B"));

        ForwardProperties props = new ForwardProperties();
        props.setRules(new ArrayList<>(List.of(r1, r2)));

        ForwardingEngine engine = new ForwardingEngine(
                mock(OpcUaService.class), registry, props);

        engine.onDataReceived(makeData("line-A", "d1", Quality.Good));

        verify(registry, times(1)).getOrCreate(any());
        verify(s, times(1)).enqueue(any());
    }

    @Test
    void onDataReceived_dropsBadDataPointsBeforeRoutingByDefault() {
        SenderRegistry registry = mock(SenderRegistry.class);
        Sender mockSender = mock(Sender.class);
        when(registry.getOrCreate(any())).thenReturn(mockSender);

        ForwardRule rule = new ForwardRule();
        ForwardTarget t = kafka("k:9092", "t");
        rule.setTargets(List.of(t));
        // 默认 qualityFilter = dropBadOnly

        ForwardProperties props = new ForwardProperties();
        props.setRules(new ArrayList<>(List.of(rule)));

        ForwardingEngine engine = new ForwardingEngine(
                mock(OpcUaService.class), registry, props);

        OpcUaDeviceData data = makeMixedQualityData(
                Quality.Good, Quality.Bad, Quality.Good);

        engine.onDataReceived(data);

        ArgumentCaptor<OpcUaDeviceData> captor = ArgumentCaptor.forClass(OpcUaDeviceData.class);
        verify(mockSender).enqueue(captor.capture());

        OpcUaDeviceData enqueued = captor.getValue();
        assertEquals(2, enqueued.getData().size(), "Bad point should be dropped");
        assertTrue(enqueued.getData().stream()
                .allMatch(p -> p.getQuality() == Quality.Good));
    }

    @Test
    void onDataReceived_skipsEnqueueWhenAllDataPointsAreBad() {
        SenderRegistry registry = mock(SenderRegistry.class);
        Sender mockSender = mock(Sender.class);
        when(registry.getOrCreate(any())).thenReturn(mockSender);

        ForwardRule rule = new ForwardRule();
        rule.setTargets(List.of(kafka("k:9092", "t")));

        ForwardProperties props = new ForwardProperties();
        props.setRules(new ArrayList<>(List.of(rule)));

        ForwardingEngine engine = new ForwardingEngine(
                mock(OpcUaService.class), registry, props);

        OpcUaDeviceData allBad = makeMixedQualityData(Quality.Bad, Quality.Bad);

        engine.onDataReceived(allBad);

        verify(mockSender, never()).enqueue(any());
    }

    @Test
    void onDataReceived_alertChannelReceivesOriginalDataIncludingBad() {
        SenderRegistry registry = mock(SenderRegistry.class);
        Sender mainSender = mock(Sender.class);
        Sender alertSender = mock(Sender.class);

        ForwardRule rule = new ForwardRule();
        rule.setTargets(List.of(kafka("k:9092", "main")));
        AlertConfig alerts = new AlertConfig();
        alerts.setEnabled(true);
        ForwardTarget alertT = new ForwardTarget();
        alertT.setType("mqtt");
        alertT.setBrokerUrl("tcp://b:1883");
        alertT.setClientId("c1");
        alertT.setTopic("alert");
        alerts.setTargets(List.of(alertT));
        rule.setAlerts(alerts);

        when(registry.getOrCreate(argThat(t -> t != null && "kafka".equals(t.getType()))))
                .thenReturn(mainSender);
        when(registry.getOrCreate(argThat(t -> t != null && "mqtt".equals(t.getType()))))
                .thenReturn(alertSender);

        ForwardProperties props = new ForwardProperties();
        props.setRules(new ArrayList<>(List.of(rule)));

        ForwardingEngine engine = new ForwardingEngine(
                mock(OpcUaService.class), registry, props);

        OpcUaDeviceData data = makeMixedQualityData(Quality.Good, Quality.Bad);

        engine.onDataReceived(data);

        // 主通道：Bad 已过滤
        ArgumentCaptor<OpcUaDeviceData> mainCap = ArgumentCaptor.forClass(OpcUaDeviceData.class);
        verify(mainSender).enqueue(mainCap.capture());
        assertEquals(1, mainCap.getValue().getData().size());

        // 告警通道：原始数据，含 Bad
        ArgumentCaptor<OpcUaDeviceData> alertCap = ArgumentCaptor.forClass(OpcUaDeviceData.class);
        verify(alertSender).enqueue(alertCap.capture());
        assertEquals(2, alertCap.getValue().getData().size());
    }

    private static OpcUaDeviceData makeMixedQualityData(Quality... qualities) {
        OpcUaDataPoint[] arr = new OpcUaDataPoint[qualities.length];
        for (int i = 0; i < qualities.length; i++) {
            arr[i] = new OpcUaDataPoint("ns=2;s=N" + i, "name", 1, "Int32",
                    qualities[i], true, qualities[i].name(),
                    Instant.now(), Instant.now());
        }
        return new OpcUaDeviceData(
                Instant.now(),
                new OpcUaDeviceData.SourceInfo("p", "d", "opc.tcp://x"),
                List.of(arr));
    }

    private static ForwardRule ruleWithProductId(String productId, ForwardTarget t) {
        ForwardRule r = new ForwardRule();
        r.setName("r-" + productId);
        r.getMatch().setProductId(productId);
        r.setTargets(List.of(t));
        return r;
    }

    static ForwardTarget kafka(String bootstrap, String topic) {
        ForwardTarget t = new ForwardTarget();
        t.setType("kafka");
        t.setBootstrapServers(bootstrap);
        t.setTopic(topic);
        return t;
    }

    static OpcUaDeviceData makeData(String pid, String did, Quality q) {
        OpcUaDataPoint p = new OpcUaDataPoint("ns=2;s=N", "name", 1, "Int32",
                q, true, q.name(), Instant.now(), Instant.now());
        return new OpcUaDeviceData(
                Instant.now(),
                new OpcUaDeviceData.SourceInfo(pid, did, "opc.tcp://x"),
                List.of(p));
    }
}