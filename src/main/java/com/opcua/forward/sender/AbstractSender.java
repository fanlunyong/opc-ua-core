package com.opcua.forward.sender;

import com.opcua.model.OpcUaDeviceData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sender 抽象基类 — 队列 + worker drain + 生命周期。
 * 子类实现 {@link #doSend(OpcUaDeviceData)} 与 {@link #doClose()}。
 */
public abstract class AbstractSender implements Sender {

    protected static final Logger logger = LoggerFactory.getLogger(AbstractSender.class);

    private final String senderId;
    private final int workerThreads;
    protected final LinkedBlockingQueue<OpcUaDeviceData> queue;
    private final List<Thread> workers = new ArrayList<>();
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final AtomicBoolean running = new AtomicBoolean(true);

    private final ConcurrentHashMap<String, AtomicLong> dropsByDevice = new ConcurrentHashMap<>();
    private final AtomicLong totalDropsSinceLastFlush = new AtomicLong();
    private volatile long lastFlushNanos = System.nanoTime();
    private static final long FLUSH_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final long FLUSH_COUNT_THRESHOLD = 100;

    /** 未处理消息数（队列中 + worker 正在 doSend 中）。
     *  enqueue 成功增 1；drop-oldest 路径 poll+offer 净变化 0；
     *  worker take 不变（queue.size 减 1 ↔ 处理中 +1）；doSend 完成（finally）减 1。
     *  awaitDrain 必须等到 pending == 0 才视为 drained，避免 take/inflight 间隙的竞争。 */
    private final AtomicLong pending = new AtomicLong();

    protected AbstractSender(String senderId, int queueCapacity, int workerThreads) {
        this.senderId = senderId;
        this.workerThreads = Math.max(1, workerThreads);
        this.queue = new LinkedBlockingQueue<>(Math.max(1, queueCapacity));
    }

    /** 子类构造完成后调用一次，启动 worker 线程 */
    protected final void start() {
        for (int i = 0; i < workerThreads; i++) {
            Thread t = new Thread(this::drainLoop, "sender-" + senderId + "-" + i);
            t.setDaemon(true);
            workers.add(t);
            t.start();
        }
    }

    @Override public String getSenderId() { return senderId; }

    @Override
    public void enqueue(OpcUaDeviceData data) {
        if (!accepting.get()) return;
        if (!queue.offer(data)) {
            queue.poll();          // poll + offer 净变化 0，pending 不变
            queue.offer(data);
            String deviceId = data.getSource().getDeviceId();
            dropsByDevice.computeIfAbsent(deviceId, k -> new AtomicLong()).incrementAndGet();
            totalDropsSinceLastFlush.incrementAndGet();
            onDropOldest(data);
            maybeFlushDropWarnings();
        } else {
            pending.incrementAndGet();
        }
    }

    /** drop-oldest 钩子；Task 8 增加聚合 WARN 实现 */
    protected void onDropOldest(OpcUaDeviceData data) { /* default noop */ }

    @Override public void stopAccepting() { accepting.set(false); }

    @Override
    public int awaitDrain(Duration timeout) {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        while (pending.get() > 0) {
            if (System.nanoTime() >= deadlineNanos) break;
            try { Thread.sleep(20); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return queue.size();
    }

    @Override
    public void shutdown(Duration timeout) {
        stopAccepting();
        int leftover = awaitDrain(timeout);
        if (leftover > 0) {
            logger.warn("Sender {} shutdown timeout, {} messages dropped", senderId, leftover);
        }
        running.set(false);
        for (Thread t : workers) t.interrupt();
        try {
            doClose();
        } catch (Exception e) {
            logger.warn("Sender {} doClose error: {}", senderId, e.getMessage());
        }
    }

    protected abstract void doSend(OpcUaDeviceData data);
    protected abstract void doClose();

    /** 测试可见：返回当前 deviceId 累计但未 flush 的 drop 计数 */
    long getDropCount(String deviceId) {
        AtomicLong a = dropsByDevice.get(deviceId);
        return a == null ? 0 : a.get();
    }

    private void maybeFlushDropWarnings() {
        long now = System.nanoTime();
        if (now - lastFlushNanos >= FLUSH_INTERVAL_NANOS
                || totalDropsSinceLastFlush.get() >= FLUSH_COUNT_THRESHOLD) {
            synchronized (this) {
                long dueNow = System.nanoTime();
                if (dueNow - lastFlushNanos >= FLUSH_INTERVAL_NANOS
                        || totalDropsSinceLastFlush.get() >= FLUSH_COUNT_THRESHOLD) {
                    flushDropWarnings();
                    lastFlushNanos = dueNow;
                }
            }
        }
    }

    private void flushDropWarnings() {
        dropsByDevice.forEach((deviceId, ctr) -> {
            long c = ctr.getAndSet(0);
            if (c > 0) {
                logger.warn("Sender {} dropped {} messages from device {}",
                        getSenderId(), c, deviceId);
            }
        });
        totalDropsSinceLastFlush.set(0);
    }

    private void drainLoop() {
        while (running.get()) {
            try {
                OpcUaDeviceData data = queue.take();
                try {
                    doSend(data);
                } catch (Exception e) {
                    logger.warn("Sender {} doSend error: {}", senderId, e.getMessage());
                } finally {
                    pending.decrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (!running.get()) break;
            }
        }
    }
}
