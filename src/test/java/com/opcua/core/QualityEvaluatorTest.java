package com.opcua.core;

import com.opcua.model.Quality;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * QualityEvaluator 单元测试。
 */
@DisplayName("QualityEvaluator")
class QualityEvaluatorTest {

    @Nested
    @DisplayName("StatusCode 到 Quality 的映射")
    class Mapping {

        @Test
        @DisplayName("isGood() 应映射为 Good")
        void shouldReturnGoodForGoodStatusCode() {
            StatusCode sc = mock(StatusCode.class);
            when(sc.isGood()).thenReturn(true);
            when(sc.isBad()).thenReturn(false);

            assertThat(QualityEvaluator.evaluate(sc)).isEqualTo(Quality.Good);
        }

        @Test
        @DisplayName("isBad() 应映射为 Bad")
        void shouldReturnBadForBadStatusCode() {
            StatusCode sc = mock(StatusCode.class);
            when(sc.isGood()).thenReturn(false);
            when(sc.isBad()).thenReturn(true);

            assertThat(QualityEvaluator.evaluate(sc)).isEqualTo(Quality.Bad);
        }

        @Test
        @DisplayName("既非 Good 也非 Bad 应映射为 Uncertain")
        void shouldReturnUncertainForOtherStatusCode() {
            StatusCode sc = mock(StatusCode.class);
            when(sc.isGood()).thenReturn(false);
            when(sc.isBad()).thenReturn(false);

            assertThat(QualityEvaluator.evaluate(sc)).isEqualTo(Quality.Uncertain);
        }

        @Test
        @DisplayName("null StatusCode 应映射为 Uncertain")
        void shouldReturnUncertainForNullStatusCode() {
            assertThat(QualityEvaluator.evaluate(null)).isEqualTo(Quality.Uncertain);
        }
    }
}
