package com.opcua.api.dto;

import com.opcua.forward.config.ForwardRule;
import com.opcua.forward.config.ForwardTarget;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ForwardRuleDTOTest {

    @Test
    void shouldConvertKafkaDtoToForwardRule() {
        ForwardRuleDTO dto = new ForwardRuleDTO();
        dto.setName("kafka-rule");
        dto.setType("KAFKA");
        dto.setEnabled(true);
        dto.setConfig(Map.of(
                "bootstrapServers", "localhost:9092",
                "topic", "opcua-data",
                "deviceId", "device-1"
        ));

        ForwardRule rule = ForwardRuleDTO.toForwardRule(dto);

        assertThat(rule.getName()).isEqualTo("kafka-rule");
        assertThat(rule.getTargets()).hasSize(1);
        ForwardTarget target = rule.getTargets().get(0);
        assertThat(target.getType()).isEqualTo("kafka");
        assertThat(target.isEnabled()).isTrue();
        assertThat(target.getBootstrapServers()).isEqualTo("localhost:9092");
        assertThat(target.getTopic()).isEqualTo("opcua-data");
        assertThat(rule.getMatch().getDeviceId()).isEqualTo("device-1");
    }

    @Test
    void shouldConvertForwardRuleToDto() {
        ForwardRule rule = new ForwardRule();
        rule.setName("kafka-rule");
        ForwardTarget target = new ForwardTarget();
        target.setType("kafka");
        target.setEnabled(true);
        target.setBootstrapServers("localhost:9092");
        target.setTopic("opcua-data");
        rule.setTargets(List.of(target));

        ForwardRuleDTO dto = ForwardRuleDTO.fromForwardRule(rule);

        assertThat(dto.getName()).isEqualTo("kafka-rule");
        assertThat(dto.getType()).isEqualTo("KAFKA");
        assertThat(dto.isEnabled()).isTrue();
        assertThat(dto.getConfig()).containsEntry("bootstrapServers", "localhost:9092");
        assertThat(dto.getConfig()).containsEntry("topic", "opcua-data");
    }

    @Test
    void shouldHandleDisabledRule() {
        ForwardRuleDTO dto = new ForwardRuleDTO();
        dto.setName("disabled-rule");
        dto.setType("HTTP");
        dto.setEnabled(false);

        ForwardRule rule = ForwardRuleDTO.toForwardRule(dto);

        assertThat(rule.getTargets()).hasSize(1);
        assertThat(rule.getTargets().get(0).isEnabled()).isFalse();
    }
}
