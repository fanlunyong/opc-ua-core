package com.opcua.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Quality 枚举单元测试。
 */
class QualityTest {

    @Test
    void shouldHaveThreeValues() {
        assertThat(Quality.values())
                .containsExactly(Quality.Good, Quality.Bad, Quality.Uncertain);
    }

    @Test
    void shouldResolveGood() {
        assertThat(Quality.valueOf("Good")).isEqualTo(Quality.Good);
    }

    @Test
    void shouldResolveBad() {
        assertThat(Quality.valueOf("Bad")).isEqualTo(Quality.Bad);
    }

    @Test
    void shouldResolveUncertain() {
        assertThat(Quality.valueOf("Uncertain")).isEqualTo(Quality.Uncertain);
    }
}
