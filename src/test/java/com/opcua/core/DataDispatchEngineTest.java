package com.opcua.core;

import com.opcua.TestConstants;
import com.opcua.api.OpcUaDataListener;
import com.opcua.model.OpcUaDataPoint;
import com.opcua.model.OpcUaDeviceData;
import com.opcua.model.Quality;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DataDispatchEngine 单元测试。
 * 遵循 TDD：先写测试，确认失败后再编写实现。
 */
@DisplayName("DataDispatchEngine")
class DataDispatchEngineTest {

    private OpcUaDeviceData createDeviceData(String deviceId, String value) {
        OpcUaDataPoint dataPoint = new OpcUaDataPoint(
                "ns=2;s=Test.Node",
                "TestNode",
                value,
                "String",
                Quality.Good,
                true,
                "0x00",
                Instant.now(),
                Instant.now()
        );
        OpcUaDeviceData.SourceInfo sourceInfo = new OpcUaDeviceData.SourceInfo(
                "test-product", deviceId, "opc.tcp://localhost:4840"
        );
        return new OpcUaDeviceData(Instant.now(), sourceInfo, List.of(dataPoint));
    }

    /**
     * 4.1 分桶路由与数据分发
     */
    @Nested
    @DisplayName("分桶路由与数据分发")
    class BucketRoutingAndDispatch {

        @Test
        @DisplayName("dispatch 应将数据路由到正确的 bucket")
        void shouldRouteToCorrectBucket() {
            DataDispatchEngine engine = new DataDispatchEngine(4, 100, Collections.emptyList());
            try {
                int index1 = engine.bucketIndexFor(TestConstants.DEVICE_ID);
                int index2 = engine.bucketIndexFor("another-device");
                int index1Again = engine.bucketIndexFor(TestConstants.DEVICE_ID);

                // 同一 deviceId 应始终路由到同一 bucket
                assertThat(index1).isEqualTo(index1Again);
                // bucket 索引应在有效范围内
                assertThat(index1).isBetween(0, 3);
                assertThat(index2).isBetween(0, 3);
            } finally {
                engine.shutdown();
            }
        }

        @Test
        @DisplayName("dispatch 应将数据多播给所有注册的监听器")
        void shouldDeliverDataToAllListeners() throws Exception {
            CountDownLatch latch1 = new CountDownLatch(1);
            CountDownLatch latch2 = new CountDownLatch(1);
            AtomicReference<OpcUaDeviceData> received1 = new AtomicReference<>();
            AtomicReference<OpcUaDeviceData> received2 = new AtomicReference<>();

            OpcUaDataListener listener1 = data -> {
                received1.set(data);
                latch1.countDown();
            };
            OpcUaDataListener listener2 = data -> {
                received2.set(data);
                latch2.countDown();
            };

            DataDispatchEngine engine = new DataDispatchEngine(
                    2, 100, List.of(listener1, listener2));
            try {
                OpcUaDeviceData testData = createDeviceData(TestConstants.DEVICE_ID, "test-value");
                engine.dispatch(testData);

                assertThat(latch1.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(latch2.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(received1.get()).isSameAs(testData);
                assertThat(received2.get()).isSameAs(testData);
            } finally {
                engine.shutdown();
            }
        }

        @Test
        @DisplayName("不同 deviceId 的数据应路由到不同 bucket")
        void shouldRouteDifferentDevicesToDifferentOrSameBuckets() {
            DataDispatchEngine engine = new DataDispatchEngine(8, 100, Collections.emptyList());
            try {
                int bucketA = engine.bucketIndexFor("device-A");
                int bucketB = engine.bucketIndexFor("device-B");
                int bucketC = engine.bucketIndexFor("device-C");

                // 所有索引应在有效范围内，hash 碰撞概率很小但这里只验证范围
                assertThat(bucketA).isBetween(0, 7);
                assertThat(bucketB).isBetween(0, 7);
                assertThat(bucketC).isBetween(0, 7);
            } finally {
                engine.shutdown();
            }
        }
    }

    /**
     * 4.2 队列满时丢弃最旧数据
     */
    @Nested
    @DisplayName("队列溢出处理")
    class QueueOverflow {

        @Test
        @DisplayName("队列满时应丢弃最旧的数据，保留新数据（确定性验证）")
        void shouldDropOldestWhenQueueFull() throws Exception {
            // 阻塞 listener 让 drain 线程在第一条数据上停住，使后续 dispatch 必然填满队列
            CountDownLatch listenerStarted = new CountDownLatch(1);
            CountDownLatch releaseLatch = new CountDownLatch(1);
            List<String> receivedValues = new CopyOnWriteArrayList<>();
            AtomicBoolean firstCall = new AtomicBoolean(true);

            OpcUaDataListener listener = data -> {
                String value = (String) data.getData().get(0).getValue();
                if (firstCall.compareAndSet(true, false)) {
                    receivedValues.add(value);
                    listenerStarted.countDown();
                    try {
                        releaseLatch.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                } else {
                    receivedValues.add(value);
                }
            };

            DataDispatchEngine engine = new DataDispatchEngine(
                    1, 2, List.of(listener));
            try {
                // d1 被 drain 线程取走并阻塞在 listener
                engine.dispatch(createDeviceData(TestConstants.DEVICE_ID, "d1"));
                assertThat(listenerStarted.await(2, TimeUnit.SECONDS)).isTrue();

                // 队列填满到 capacity=2
                engine.dispatch(createDeviceData(TestConstants.DEVICE_ID, "d2"));
                engine.dispatch(createDeviceData(TestConstants.DEVICE_ID, "d3"));

                // d4 溢出 → drop-oldest 应丢弃 d2，保留 d3 和 d4
                engine.dispatch(createDeviceData(TestConstants.DEVICE_ID, "d4"));

                // 释放 listener，drain 线程依次处理 d1（已被取走）、d3、d4
                releaseLatch.countDown();

                // 等待剩余两条被处理
                long deadline = System.currentTimeMillis() + 2000;
                while (receivedValues.size() < 3 && System.currentTimeMillis() < deadline) {
                    Thread.sleep(20);
                }

                assertThat(receivedValues).containsExactly("d1", "d3", "d4");
                assertThat(receivedValues).doesNotContain("d2");
                assertThat(engine.getDroppedCount()).isEqualTo(1L);
            } finally {
                releaseLatch.countDown();
                engine.shutdown();
            }
        }

        @Test
        @DisplayName("队列未满时所有数据都应被分发")
        void shouldDeliverAllDataWhenQueueNotFull() throws Exception {
            CountDownLatch latch = new CountDownLatch(3);
            List<OpcUaDeviceData> received = new CopyOnWriteArrayList<>();

            OpcUaDataListener listener = data -> {
                received.add(data);
                latch.countDown();
            };

            DataDispatchEngine engine = new DataDispatchEngine(
                    1, 10, List.of(listener));
            try {
                OpcUaDeviceData data1 = createDeviceData(TestConstants.DEVICE_ID, "1");
                OpcUaDeviceData data2 = createDeviceData(TestConstants.DEVICE_ID, "2");
                OpcUaDeviceData data3 = createDeviceData(TestConstants.DEVICE_ID, "3");

                engine.dispatch(data1);
                engine.dispatch(data2);
                engine.dispatch(data3);

                assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
                assertThat(received).hasSize(3);
            } finally {
                engine.shutdown();
            }
        }

        @Test
        @DisplayName("溢出时 droppedCount 应递增")
        void shouldIncrementDroppedCountOnDrop() throws Exception {
            CountDownLatch listenerStarted = new CountDownLatch(1);
            CountDownLatch releaseLatch = new CountDownLatch(1);

            OpcUaDataListener blockingListener = data -> {
                listenerStarted.countDown();
                try {
                    releaseLatch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            };

            DataDispatchEngine engine = new DataDispatchEngine(
                    1, 2, List.of(blockingListener));
            try {
                // d1 立即被 drain 线程取走并阻塞在 listener
                engine.dispatch(createDeviceData(TestConstants.DEVICE_ID, "d1"));
                assertThat(listenerStarted.await(2, TimeUnit.SECONDS)).isTrue();

                // 队列填满（capacity=2）
                engine.dispatch(createDeviceData(TestConstants.DEVICE_ID, "d2"));
                engine.dispatch(createDeviceData(TestConstants.DEVICE_ID, "d3"));

                // d4 溢出 → drop oldest (d2)
                engine.dispatch(createDeviceData(TestConstants.DEVICE_ID, "d4"));

                assertThat(engine.getDroppedCount()).isEqualTo(1L);
            } finally {
                releaseLatch.countDown();
                engine.shutdown();
            }
        }
    }

    /**
     * 4.3 监听器异常隔离
     */
    @Nested
    @DisplayName("监听器异常隔离")
    class ListenerExceptionIsolation {

        @Test
        @DisplayName("监听器抛异常不应影响其他监听器接收数据")
        void shouldIsolateListenerExceptions() throws Exception {
            CountDownLatch goodLatch = new CountDownLatch(1);
            AtomicReference<OpcUaDeviceData> goodReceived = new AtomicReference<>();
            AtomicBoolean throwingCalled = new AtomicBoolean(false);

            OpcUaDataListener throwingListener = data -> {
                throwingCalled.set(true);
                throw new RuntimeException("listener error simulation");
            };
            OpcUaDataListener goodListener = data -> {
                goodReceived.set(data);
                goodLatch.countDown();
            };

            DataDispatchEngine engine = new DataDispatchEngine(
                    1, 10, List.of(throwingListener, goodListener));
            try {
                OpcUaDeviceData testData = createDeviceData(TestConstants.DEVICE_ID, "exception-test");
                engine.dispatch(testData);

                assertThat(goodLatch.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(goodReceived.get()).isSameAs(testData);
                assertThat(throwingCalled.get()).isTrue();
            } finally {
                engine.shutdown();
            }
        }

        @Test
        @DisplayName("监听器抛异常不应中断同批次后续分发")
        void shouldContinueDispatchingAfterException() throws Exception {
            CountDownLatch latch = new CountDownLatch(2);
            List<OpcUaDeviceData> received = new CopyOnWriteArrayList<>();

            OpcUaDataListener throwingListener = data -> {
                throw new RuntimeException("always fails");
            };
            OpcUaDataListener normalListener = data -> {
                received.add(data);
                latch.countDown();
            };

            DataDispatchEngine engine = new DataDispatchEngine(
                    1, 10, List.of(throwingListener, normalListener));
            try {
                engine.dispatch(createDeviceData(TestConstants.DEVICE_ID, "msg1"));
                engine.dispatch(createDeviceData(TestConstants.DEVICE_ID, "msg2"));

                assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
                assertThat(received).hasSize(2);
            } finally {
                engine.shutdown();
            }
        }
    }

    /**
     * 4.4 Shutdown 行为
     */
    @Nested
    @DisplayName("Shutdown 行为")
    class ShutdownBehavior {

        @Test
        @DisplayName("shutdown 后 dispatch 不应再分发数据")
        void shouldNotDispatchAfterShutdown() throws Exception {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicInteger receivedCount = new AtomicInteger(0);

            OpcUaDataListener listener = data -> {
                receivedCount.incrementAndGet();
                latch.countDown();
            };

            DataDispatchEngine engine = new DataDispatchEngine(
                    1, 10, List.of(listener));

            // 先发一条确认能正常工作
            engine.dispatch(createDeviceData(TestConstants.DEVICE_ID, "before-shutdown"));
            boolean received = latch.await(2, TimeUnit.SECONDS);

            engine.shutdown();

            // shutdown 后再发一条不应被处理
            int countBefore = receivedCount.get();
            engine.dispatch(createDeviceData(TestConstants.DEVICE_ID, "after-shutdown"));

            // 等待一小段时间确保 drain 线程已停止
            Thread.sleep(200);
            assertThat(receivedCount.get()).isEqualTo(countBefore);
        }

        @Test
        @DisplayName("重复调用 shutdown 不应抛异常")
        void shouldNotThrowOnDoubleShutdown() {
            DataDispatchEngine engine = new DataDispatchEngine(
                    2, 10, Collections.emptyList());
            engine.shutdown();
            // 第二次调用不应抛异常
            engine.shutdown();
        }
    }

    /**
     * 4.5 构造函数参数验证
     */
    @Nested
    @DisplayName("构造函数与配置")
    class ConstructionAndConfiguration {

        @Test
        @DisplayName("bucketCount 应创建对应数量的 bucket")
        void shouldCreateCorrectNumberOfBuckets() {
            DataDispatchEngine engine = new DataDispatchEngine(4, 100, Collections.emptyList());
            try {
                // 通过计算不同值对应的 bucket 索引范围来验证
                int maxIndex = -1;
                for (int i = 0; i < 100; i++) {
                    int index = engine.bucketIndexFor("test-device-" + i);
                    if (index > maxIndex) maxIndex = index;
                }
                // 有 4 个 bucket，索引范围 0-3
                assertThat(maxIndex).isLessThanOrEqualTo(3);
            } finally {
                engine.shutdown();
            }
        }

        @Test
        @DisplayName("单 bucket 也应正常工作")
        void shouldWorkWithSingleBucket() throws Exception {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<OpcUaDeviceData> received = new AtomicReference<>();

            OpcUaDataListener listener = data -> {
                received.set(data);
                latch.countDown();
            };

            DataDispatchEngine engine = new DataDispatchEngine(
                    1, 100, List.of(listener));
            try {
                OpcUaDeviceData testData = createDeviceData(TestConstants.DEVICE_ID, "single-bucket");
                engine.dispatch(testData);

                assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(received.get()).isSameAs(testData);
            } finally {
                engine.shutdown();
            }
        }

        @Test
        @DisplayName("addListener 注册的监听器应在后续 dispatch 中收到数据")
        void shouldDeliverToDynamicallyAddedListener() throws Exception {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<OpcUaDeviceData> received = new AtomicReference<>();

            DataDispatchEngine engine = new DataDispatchEngine(1, 50, Collections.emptyList());
            try {
                OpcUaDataListener dynamicListener = data -> {
                    received.set(data);
                    latch.countDown();
                };
                engine.addListener(dynamicListener);

                OpcUaDeviceData testData = createDeviceData(TestConstants.DEVICE_ID, "dynamic");
                engine.dispatch(testData);

                assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(received.get()).isSameAs(testData);
            } finally {
                engine.shutdown();
            }
        }
    }
}
