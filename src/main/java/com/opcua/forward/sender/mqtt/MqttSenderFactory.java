package com.opcua.forward.sender.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opcua.forward.config.ForwardTarget;
import com.opcua.forward.sender.ConnectionFingerprint;
import com.opcua.forward.sender.Sender;
import com.opcua.forward.sender.SenderFactory;
import com.opcua.forward.sender.SharedConnection;
import org.eclipse.paho.client.mqttv3.IMqttClient;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.util.concurrent.atomic.AtomicLong;

public class MqttSenderFactory implements SenderFactory {

    private final ObjectMapper objectMapper;
    private final AtomicLong counter = new AtomicLong();

    public MqttSenderFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public SharedConnection<?> createConnection(ConnectionFingerprint fp, ForwardTarget target) {
        try {
            IMqttClient client = new MqttClient(
                    target.getBrokerUrl(),
                    target.getClientId(),
                    new MemoryPersistence());
            MqttConnectOptions opts = new MqttConnectOptions();
            opts.setAutomaticReconnect(true);
            opts.setCleanSession(true);
            client.connect(opts);
            return new SharedConnection<>(client, () -> {
                try { client.disconnect(); } catch (MqttException ignored) { }
                try { client.close(); } catch (MqttException ignored) { }
            });
        } catch (MqttException e) {
            throw new IllegalStateException("Failed to connect MQTT broker: " + target.getBrokerUrl(), e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Sender createSender(ForwardTarget target, SharedConnection<?> connection) {
        SharedConnection<IMqttClient> conn = (SharedConnection<IMqttClient>) connection;
        return new MqttSender("mqtt-" + counter.incrementAndGet(), target, conn, objectMapper);
    }
}