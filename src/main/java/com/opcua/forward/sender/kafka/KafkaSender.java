package com.opcua.forward.sender.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opcua.forward.config.ForwardTarget;
import com.opcua.forward.sender.AbstractSender;
import com.opcua.forward.sender.SharedConnection;
import com.opcua.forward.util.PlaceholderResolver;
import com.opcua.model.OpcUaDeviceData;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;

public class KafkaSender extends AbstractSender {

    private final SharedConnection<KafkaProducer<String, String>> connection;
    private final String topicTemplate;
    private final ObjectMapper objectMapper;

    public KafkaSender(String senderId,
                       ForwardTarget target,
                       SharedConnection<KafkaProducer<String, String>> connection,
                       ObjectMapper objectMapper) {
        super(senderId, target.getQueueCapacity(), target.getWorkerThreads());
        this.connection = connection;
        this.topicTemplate = target.getTopic();
        this.objectMapper = objectMapper;
        start();
    }

    @Override
    protected void doSend(OpcUaDeviceData data) {
        String topic = PlaceholderResolver.resolve(topicTemplate, data);
        String key = data.getSource().getDeviceId();
        String value;
        try {
            value = objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            logger.warn("Sender {} JSON serialize error: {}", getSenderId(), e.getMessage());
            return;
        }
        connection.get().send(new ProducerRecord<>(topic, key, value));
    }

    @Override
    protected void doClose() {
        connection.release();
    }
}