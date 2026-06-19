package com.opcua.forward.engine;

import com.opcua.api.OpcUaService;
import com.opcua.forward.config.ForwardProperties;
import com.opcua.forward.config.ForwardRule;
import com.opcua.forward.config.ForwardTarget;
import com.opcua.forward.sender.SenderRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.Mockito.*;

class ForwardingEngineLifecycleTest {

    @Test
    void init_emptyRules_doesNotRegister() {
        OpcUaService svc = mock(OpcUaService.class);
        SenderRegistry reg = mock(SenderRegistry.class);
        ForwardProperties props = new ForwardProperties();
        // empty rules

        ForwardingEngine engine = new ForwardingEngine(svc, reg, props);
        engine.init();

        verifyNoInteractions(svc);
    }

    @Test
    void init_nonEmptyRules_registersListener() {
        OpcUaService svc = mock(OpcUaService.class);
        SenderRegistry reg = mock(SenderRegistry.class);

        ForwardRule rule = new ForwardRule();
        rule.setName("r");
        ForwardTarget t = new ForwardTarget(); t.setType("kafka"); t.setBootstrapServers("k:9092");
        rule.setTargets(List.of(t));
        ForwardProperties props = new ForwardProperties();
        props.setRules(new ArrayList<>(List.of(rule)));

        ForwardingEngine engine = new ForwardingEngine(svc, reg, props);
        engine.init();

        verify(svc).registerListener(engine);
    }

    @Test
    void shutdown_unregistersAndShutsDownRegistry() {
        OpcUaService svc = mock(OpcUaService.class);
        SenderRegistry reg = mock(SenderRegistry.class);

        ForwardProperties props = new ForwardProperties();
        props.setShutdownTimeout(Duration.ofSeconds(2));

        ForwardingEngine engine = new ForwardingEngine(svc, reg, props);
        engine.shutdown();

        verify(svc).unregisterListener(engine);
        verify(reg).shutdown(Duration.ofSeconds(2));
    }
}