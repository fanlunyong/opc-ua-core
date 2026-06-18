package com.opcua.core;

import com.opcua.model.NodeConfig;
import com.opcua.model.PollingNodeConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DataMapper 单元测试。
 */
@DisplayName("DataMapper")
class DataMapperTest {

    @Nested
    @DisplayName("displayName 解析")
    class DisplayNameResolution {

        @Test
        @DisplayName("配置已设置 displayName 时直接使用")
        void shouldUseConfiguredDisplayName() {
            NodeConfig config = new NodeConfig();
            config.setNodeId("ns=2;s=Temperature");
            config.setDisplayName("温度");

            assertThat(DataMapper.resolveDisplayName(config.getDisplayName(), config.getNodeId()))
                    .isEqualTo("温度");
        }

        @Test
        @DisplayName("displayName 为空时回退到 nodeId")
        void shouldFallbackToNodeIdWhenDisplayNameEmpty() {
            NodeConfig config = new NodeConfig();
            config.setNodeId("ns=2;s=Pressure");
            config.setDisplayName("");

            assertThat(DataMapper.resolveDisplayName(config.getDisplayName(), config.getNodeId()))
                    .isEqualTo("ns=2;s=Pressure");
        }

        @Test
        @DisplayName("displayName 为 null 时回退到 nodeId")
        void shouldFallbackToNodeIdWhenDisplayNameNull() {
            assertThat(DataMapper.resolveDisplayName(null, "ns=2;s=Flow")).isEqualTo("ns=2;s=Flow");
        }

        @Test
        @DisplayName("PollingNodeConfig 同样支持回退")
        void shouldHandlePollingConfigFallback() {
            PollingNodeConfig config = new PollingNodeConfig();
            config.setNodeId("ns=2;s=Counter");

            assertThat(DataMapper.resolveDisplayName(config.getDisplayName(), config.getNodeId()))
                    .isEqualTo("ns=2;s=Counter");
        }
    }

    @Nested
    @DisplayName("dataType 解析")
    class DataTypeResolution {

        @Test
        @DisplayName("配置已设置 dataType 时直接使用")
        void shouldUseConfiguredDataType() {
            assertThat(DataMapper.resolveDataType("Float", 42.5)).isEqualTo("Float");
        }

        @Test
        @DisplayName("dataType 未配置时从值类型推断")
        void shouldInferFromValueClass() {
            assertThat(DataMapper.resolveDataType(null, 42)).isEqualTo("Integer");
            assertThat(DataMapper.resolveDataType(null, "hello")).isEqualTo("String");
            assertThat(DataMapper.resolveDataType("", 1.5)).isEqualTo("Double");
        }

        @Test
        @DisplayName("dataType 未配置且值为 null 时返回 null")
        void shouldReturnNullWhenBothMissing() {
            assertThat(DataMapper.resolveDataType(null, null)).isNull();
            assertThat(DataMapper.resolveDataType("", null)).isNull();
        }
    }
}
