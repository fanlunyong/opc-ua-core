package com.opcua.forward.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opcua.api.OpcUaService;
import com.opcua.forward.engine.ForwardingEngine;
import com.opcua.forward.sender.CompositeSenderFactory;
import com.opcua.forward.sender.SenderFactory;
import com.opcua.forward.sender.SenderRegistry;
import com.opcua.forward.sender.http.HttpSenderFactory;
import com.opcua.forward.sender.influxdb.InfluxDBSenderFactory;
import com.opcua.forward.sender.kafka.KafkaSenderFactory;
import com.opcua.forward.sender.mqtt.MqttSenderFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * OPC UA 转发模块自动配置。
 *
 * <p>仅在 {@code opcua.forward.enabled=true}（默认）时激活。</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "opcua.forward", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ForwardProperties.class)
public class OpcUaForwardAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public CompositeSenderFactory compositeSenderFactory(ObjectMapper objectMapper) {
        Map<String, SenderFactory> map = new HashMap<>();
        map.put("kafka", new KafkaSenderFactory(objectMapper));
        map.put("influxdb", new InfluxDBSenderFactory());
        map.put("mqtt", new MqttSenderFactory(objectMapper));
        map.put("http", new HttpSenderFactory(objectMapper));
        return new CompositeSenderFactory(map);
    }

    @Bean(destroyMethod = "")
    @ConditionalOnMissingBean
    public SenderRegistry senderRegistry(CompositeSenderFactory factory) {
        return new SenderRegistry(factory);
    }

    @Bean
    @ConditionalOnMissingBean
    public ForwardingEngine forwardingEngine(OpcUaService opcUaService,
                                             SenderRegistry senderRegistry,
                                             ForwardProperties properties) {
        return new ForwardingEngine(opcUaService, senderRegistry, properties);
    }
}