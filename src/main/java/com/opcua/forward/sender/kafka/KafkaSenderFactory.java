package com.opcua.forward.sender.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opcua.forward.config.ForwardTarget;
import com.opcua.forward.sender.ConnectionFingerprint;
import com.opcua.forward.sender.Sender;
import com.opcua.forward.sender.SenderFactory;
import com.opcua.forward.sender.SharedConnection;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;

public class KafkaSenderFactory implements SenderFactory {

    private final ObjectMapper objectMapper;
    private final AtomicLong counter = new AtomicLong();

    public KafkaSenderFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public SharedConnection<?> createConnection(ConnectionFingerprint fp, ForwardTarget target) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, target.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        if (target.getSecurityProtocol() != null) {
            props.put("security.protocol", target.getSecurityProtocol());
        }
        // 透传扩展属性
        target.getProperties().forEach(props::put);

        KafkaProducer<String, String> producer = new KafkaProducer<>(props);
        return new SharedConnection<>(producer, producer::close);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Sender createSender(ForwardTarget target, SharedConnection<?> connection) {
        SharedConnection<KafkaProducer<String, String>> conn =
                (SharedConnection<KafkaProducer<String, String>>) connection;
        String id = "kafka-" + counter.incrementAndGet();
        return new KafkaSender(id, target, conn, objectMapper);
    }
}