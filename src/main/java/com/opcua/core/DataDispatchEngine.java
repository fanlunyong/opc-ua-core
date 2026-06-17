package com.opcua.core;

import com.opcua.api.OpcUaDataListener;
import com.opcua.model.OpcUaDeviceData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 数据分桶异步分发引擎。
 *
 * <p>将同一 deviceId 的数据路由到固定 bucket，保证单设备数据有序处理。
 * 每个 bucket 由一个 daemon 线程从阻塞队列中取出数据并多播给所有监听器。</p>
 *
 * <pre>{@code
 *   subscribeCallback(OpcUaDeviceData)
 *           │
 *           ▼
 *     deviceId.hashCode() % N → 路由到 Bucket[K]
 *           │
 *           ▼
 *     Bucket[0]        Bucket[1]        ...        Bucket[N-1]
 *     LinkedBlockingQueue(QUEUE_CAPACITY)
 *     queue.offer(data) — 队列满时 drop-oldest
 *     drain 线程从 queue.take() 取出，多播给所有 Listener
 * }</pre>
 */
public class DataDispatchEngine {

    private static final Logger logger = LoggerFactory.getLogger(DataDispatchEngine.class);

    private final int bucketCount;
    private final List<LinkedBlockingQueue<OpcUaDeviceData>> buckets;
    private final List<Thread> drainThreads;
    private final List<OpcUaDataListener> listeners;
    private volatile boolean running;

    /**
     * 构造分桶分发引擎。
     *
     * @param bucketCount  分桶数量
     * @param queueCapacity 每个 bucket 队列的容量
     * @param listeners    数据监听器列表（构造后不可变，内部使用 CopyOnWriteArrayList）
     */
    public DataDispatchEngine(int bucketCount, int queueCapacity, List<OpcUaDataListener> listeners) {
        this.bucketCount = bucketCount;
        this.listeners = new CopyOnWriteArrayList<>(listeners);
        this.buckets = new ArrayList<>(bucketCount);
        this.drainThreads = new ArrayList<>(bucketCount);
        this.running = true;

        for (int i = 0; i < bucketCount; i++) {
            LinkedBlockingQueue<OpcUaDeviceData> queue = new LinkedBlockingQueue<>(queueCapacity);
            buckets.add(queue);
            final int bucketIdx = i;
            Thread drainThread = new Thread(
                    () -> drainLoop(bucketIdx),
                    "opcua-dispatch-" + bucketIdx
            );
            drainThread.setDaemon(true);
            drainThreads.add(drainThread);
            drainThread.start();
        }
    }

    /**
     * 将设备数据路由到对应的 bucket 队列。
     * 队列满时丢弃最旧的数据。
     *
     * @param data 设备数据
     */
    public void dispatch(OpcUaDeviceData data) {
        if (!running) {
            return;
        }
        String deviceId = data.getSource().getDeviceId();
        int bucketIndex = Math.abs(deviceId.hashCode() % bucketCount);
        LinkedBlockingQueue<OpcUaDeviceData> queue = buckets.get(bucketIndex);
        // 队列满时丢弃最旧元素（drop-oldest）
        while (!queue.offer(data)) {
            queue.poll();
        }
    }

    /**
     * 停止所有 drain 线程。
     * 可重复调用，不抛异常。
     */
    public void shutdown() {
        running = false;
        for (Thread thread : drainThreads) {
            thread.interrupt();
        }
    }

    /**
     * 计算指定 deviceId 应路由到的 bucket 索引（测试用）。
     */
    int bucketIndexFor(String deviceId) {
        return Math.abs(deviceId.hashCode() % bucketCount);
    }

    /**
     * Drain 线程主循环：从队列中阻塞取出数据，多播给所有监听器。
     */
    private void drainLoop(int bucketIndex) {
        LinkedBlockingQueue<OpcUaDeviceData> queue = buckets.get(bucketIndex);
        while (running) {
            try {
                OpcUaDeviceData data = queue.take();
                for (OpcUaDataListener listener : listeners) {
                    try {
                        listener.onDataReceived(data);
                    } catch (Exception e) {
                        logger.warn("监听器回调异常: bucket={}, listener={}, error={}",
                                bucketIndex, listener.getClass().getSimpleName(), e.getMessage());
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (!running) {
                    break;
                }
            }
        }
    }
}
