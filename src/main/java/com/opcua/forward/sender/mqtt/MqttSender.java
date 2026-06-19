package com.opcua.forward.sender.mqtt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opcua.forward.config.ForwardTarget;
import com.opcua.forward.sender.AbstractSender;
import com.opcua.forward.sender.SharedConnection;
import com.opcua.forward.util.PlaceholderResolver;
import com.opcua.model.OpcUaDeviceData;
import org.eclipse.paho.client.mqttv3.IMqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;

public class MqttSender extends AbstractSender {

    private final SharedConnection<IMqttClient> connection;
    private final String topicTemplate;
    private final int qos;
    private final ObjectMapper objectMapper;

    public MqttSender(String senderId, ForwardTarget target,
                      SharedConnection<IMqttClient> connection, ObjectMapper objectMapper) {
        super(senderId, target.getQueueCapacity(), target.getWorkerThreads());
        this.connection = connection;
        this.topicTemplate = target.getTopic();
        this.qos = target.getQos();
        this.objectMapper = objectMapper;
        start();
    }

    @Override
    protected void doSend(OpcUaDeviceData data) {
        String topic = PlaceholderResolver.resolve(topicTemplate, data);
        byte[] payload;
        try {
            payload = objectMapper.writeValueAsBytes(data);
        } catch (JsonProcessingException e) {
            logger.warn("Sender {} JSON error: {}", getSenderId(), e.getMessage());
            return;
        }
        MqttMessage msg = new MqttMessage(payload);
        msg.setQos(qos);
        try {
            connection.get().publish(topic, msg);
        } catch (MqttException e) {
            logger.warn("Sender {} publish error: {}", getSenderId(), e.getMessage());
        }
    }

    @Override
    protected void doClose() {
        connection.release();
    }
}