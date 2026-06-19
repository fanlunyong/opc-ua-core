package com.opcua.forward.sender.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opcua.forward.config.ForwardTarget;
import com.opcua.forward.sender.SharedConnection;
import com.opcua.model.OpcUaDataPoint;
import com.opcua.model.OpcUaDeviceData;
import com.opcua.model.Quality;
import org.eclipse.paho.client.mqttv3.IMqttClient;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class MqttSenderTest {

    @Test
    void doSend_publishesJsonAtConfiguredQos() throws Exception {
        IMqttClient client = mock(IMqttClient.class);
        SharedConnection<IMqttClient> conn = new SharedConnection<>(client, () -> { });
        conn.acquire();

        ForwardTarget target = new ForwardTarget();
        target.setType("mqtt");
        target.setBrokerUrl("tcp://b:1883");
        target.setClientId("c1");
        target.setTopic("opcua/{productId}/{deviceId}");
        target.setQos(2);
        target.setQueueCapacity(8);
        target.setWorkerThreads(1);

        MqttSender sender = new MqttSender("mqtt-1", target, conn,
                new ObjectMapper().findAndRegisterModules());
        try {
            sender.enqueue(makeData("line-A", "furnace-1"));

            ArgumentCaptor<MqttMessage> msg = ArgumentCaptor.forClass(MqttMessage.class);
            verify(client, timeout(500)).publish(eq("opcua/line-A/furnace-1"), msg.capture());

            assertEquals(2, msg.getValue().getQos());
            String json = new String(msg.getValue().getPayload());
            assertTrue(json.contains("\"productId\":\"line-A\""));
        } finally {
            sender.shutdown(Duration.ofSeconds(1));
        }
    }

    private OpcUaDeviceData makeData(String productId, String deviceId) {
        OpcUaDataPoint p = new OpcUaDataPoint(
                "ns=2;s=N", "name", 1, "Int32", Quality.Good, true,
                "Good", Instant.now(), Instant.now());
        return new OpcUaDeviceData(
                Instant.now(),
                new OpcUaDeviceData.SourceInfo(productId, deviceId, "opc.tcp://x"),
                List.of(p));
    }
}