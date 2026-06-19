# Brainstorm Summary

- Change: opc-ua-forward
- Date: 2026-06-18
- Status: ready-for-review

## 已确认的事实

- **基础架构**：ForwardingEngine 实现 `OpcUaDataListener.onDataReceived(OpcUaDeviceData)`，订阅 Change 1 (`opc-ua-core`) 的设备级数据
- **数据模型**：`OpcUaDeviceData{productId, deviceId, endpointUrl, timestamp, data[OpcUaDataPoint]}`，`OpcUaDataPoint{nodeId, displayName, value, dataType, quality, qualityCheck, statusCode, sourceTimestamp, serverTimestamp}`
- **Sender 类型**：Kafka / InfluxDB / MQTT / HTTP（4 种）
- **配置驱动**：YAML `forward.rules[].{match, targets, alerts}`，支持 `${ENV}` 环境变量
- **失败策略**：drop-oldest 背压，发送失败仅记 ERROR 日志，不重试
- **Out of scope**：Schema Registry、消息去重、告警分级聚合（"本期不做告警去重"——保留原决策）、自定义插件、动态配置（Change 3）

## 已确认的设计决策

### D1: 异步边界 — 入口同步匹配，Sender 异步

- `ForwardingEngine.onDataReceived` 在 Change 1 DataDispatchEngine bucket 线程上**同步**执行规则匹配
- 匹配后调用 `sender.enqueue(data)`，Sender 内部独立队列 + worker drain
- 规则匹配必须纯 CPU，禁 I/O 禁 spam log
- 适用规模：规则数 ≤ 10、target 数 ≤ 30

### D2: Sender 共享粒度 — 按连接参数指纹聚合

- KafkaSender：按 `bootstrap-servers + securityConfig` 指纹共享 Producer（Producer 线程安全）
- MqttSender：按 `brokerUrl + clientId` 指纹共享 Client
- InfluxDBSender：按 `url + org + token` 指纹共享 Client
- HttpSender：按 type 单例（HTTP 无连接状态）
- 队列 + worker 仍 per-target（topic/url 路由解耦，避免慢 target 拖慢同 sender 内其他 target）
- 实现：`SenderRegistry` 维护 `Map<connectionFingerprint, SenderConnection>` + 引用计数

### D3: 告警路由 — 通用 targets

- `alerts.targets` 支持任意 sender 类型（Kafka/MQTT/HTTP/InfluxDB）
- 触发条件：data[] 中**任意一条** quality=Bad/Uncertain → 整个 OpcUaDeviceData 推送到所有 alerts.targets（与主 targets 同 JSON 格式）
- alerts.enabled=false 或无 alerts 节 → 不推送告警
- **Spec Patch 需求**：proposal.md 中"告警仅推送到 Kafka 告警 topic"措辞需修订为"告警可路由到任意配置的 sender 类型"

### D4: 测试策略 — 全 Mock

- 不引入 Testcontainers / EmbeddedKafkaBroker / Moquette 等嵌入式 broker
- Kafka：mock `KafkaProducer.send`，验证 ProducerRecord
- MQTT：mock `IMqttClient.publish`，验证 topic + payload
- InfluxDB：mock `WriteApi.writePoints`，验证 Point 列表
- HTTP：mock `RestTemplate` 或 `WebClient.exchange`
- 端到端：构造 fake OpcUaDeviceData → ForwardingEngine → 验证 mock sender 收到正确序列化 JSON
- **Spec Patch 需求**：tasks.md 9.1 "嵌入式 Kafka 集成测试" → "Mock-based 集成测试"

### D5: 关停语义 — 优雅关停（5s 超时）

```
1. ForwardingEngine.unregister() — 从 OpcUaService 注销 listener
2. 各 Sender 标记 stopAccepting，保留队列内已 enqueue 消息
3. 等待 senders drain，最多 5s（默认，可配置 forward.shutdownTimeout）
4. 超时未 drain：log WARN（含未发送消息数）+ 强制 close
5. close 共享 Producer/Client（按引用计数）
```

### D6: Drop-oldest 计数 — (deviceId, senderId) 聚合，1s 或 100 条 flush

```java
class Sender {
  ConcurrentHashMap<String, AtomicLong> dropsByDevice;  // key = deviceId
  AtomicLong totalDropsSinceLastFlush;
  volatile long lastFlushNanos;

  void enqueue(OpcUaDeviceData data) {
    while (!queue.offer(data)) {
      queue.poll();
      dropsByDevice.computeIfAbsent(data.getDeviceId(), k -> new AtomicLong()).incrementAndGet();
      totalDropsSinceLastFlush.incrementAndGet();
    }
    maybeFlushDropWarnings();
  }

  void maybeFlushDropWarnings() {
    long now = System.nanoTime();
    if (now - lastFlushNanos >= 1_000_000_000L
        || totalDropsSinceLastFlush.get() >= 100) {
      flushDropWarnings();
      lastFlushNanos = now;
    }
  }

  void flushDropWarnings() {
    dropsByDevice.forEach((deviceId, ctr) -> {
      long c = ctr.getAndSet(0);
      if (c > 0) logger.warn("Sender {} dropped {} messages from device {}",
                             senderId, c, deviceId);
    });
    totalDropsSinceLastFlush.set(0);
  }
}
```

- 同 (deviceId, senderId) 合并为 1 条 WARN
- 不同 deviceId 或不同 sender 不合并
- Flush 触发：1 秒过期 **或** 累积 100 条 drop（先到先触发）

### D7: Bean 生命周期与序列化（约定）

- `ForwardingEngine` 标注 `@Service`，`@ConditionalOnProperty(prefix="opcua.forward", name="enabled", matchIfMissing=true)`
- `@PostConstruct` 自动调用 `OpcUaService.registerListener(this)`
- `@PreDestroy` 触发 D5 关停流程
- 序列化复用 Change 1 的 Jackson `ObjectMapper` Bean，避免重复配置
- 若 `forward.rules` 列表为空 → engine 仍创建但跳过所有 sender 初始化（dryrun mode）

## Spec Patch 候选

待用户确认设计方案后，统一提交以下 spec / tasks 修订：

1. **proposal.md** — 告警措辞修订：
   ```
   旧：告警消息仅推送到 Kafka 告警 topic
   新：告警消息可路由到任意配置的 sender 类型（Kafka/MQTT/HTTP/InfluxDB）
   ```

2. **tasks.md 9.1** — 测试策略修订：
   ```
   旧：编写 Kafka Sender 单元测试 + 嵌入式 Kafka 集成测试
   新：编写 Kafka Sender 单元测试（Mock KafkaProducer 验证 ProducerRecord）
   ```

3. **tasks.md 9.2** — 测试策略修订：
   ```
   旧：编写 InfluxDB Sender 集成测试（使用 InfluxDB 内存实例或 Testcontainers）
   新：编写 InfluxDB Sender 单元测试（Mock WriteApi 验证 Point 转换）
   ```

4. **tasks.md 9.4** — 措辞修订：
   ```
   旧：编写端到端测试：模拟数据从 Change 1 DataListener → ForwardingEngine → Kafka/InfluxDB 全链路
   新：编写端到端测试：构造 OpcUaDeviceData → ForwardingEngine → 验证 mock senders 收到正确 JSON 与路由
   ```

5. **tasks.md 新增** — Drop-oldest 聚合实现（来自 D6）：
   ```
   2.4 实现 Drop-oldest 计数聚合：(deviceId, senderId) 维度，1s 或 100 条 flush WARN
   ```

6. **tasks.md 新增** — 优雅关停（来自 D5）：
   ```
   2.5 实现 ForwardingEngine 关停流程：listener 注销 + sender drain（5s 超时）+ 共享连接 close
   ```
