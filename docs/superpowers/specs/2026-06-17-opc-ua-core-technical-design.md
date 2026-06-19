---
comet_change: opc-ua-core
role: technical-design
canonical_spec: openspec
archived-with: 2026-06-18-opc-ua-core
status: final
---

# OPC UA 核心采集层 — 技术设计

## 1. 架构总览

```
┌─────────────────────────────────────────────────────────┐
│                    opc-ua-core                           │
├─────────────────────────────────────────────────────────┤
│  API Layer (Public Interface)                           │
│  • OpcUaService — 统一入口 + 回调分发线程池              │
│  • OpcUaDataListener — 设备级批量回调接口                │
├─────────────────────────────────────────────────────────┤
│  Core Layer                                             │
│  • ConnectionManager — 有界连接池、设备生命周期管理       │
│  • SubscriptionManager — 订阅创建/维护/重建              │
│  • ReadWriteHandler — 轮询调度 + 写入控制                │
│  • DataMapper — NodeId → 业务名称映射                   │
│  • QualityEvaluator — StatusCode → Good/Bad/Uncertain   │
├─────────────────────────────────────────────────────────┤
│  Client Wrapper Layer                                   │
│  • MiloClientWrapper — Eclipse Milo OpcUaClient 封装     │
│  • 断线重连（指数退避 1s → 60s）                          │
│  • 证书加载、安全策略配置                                 │
└─────────────────────────────────────────────────────────┘
```

## 2. 线程模型

### 2.1 三层线程池

```
┌──────────────────────────────────────────────────────────────┐
│                      线程池架构                                │
├──────────────────────────────────────────────────────────────┤
│                                                              │
│  [Milo I/O 线程]  ──▶  [回调分发线程池]  ──▶  [下游处理]       │
│   (Milo 内部管理)       (OpcUaService 管理)    (Change 2 自己)  │
│                                                              │
│  [轮询调度线程池]                                              │
│   (ScheduledExecutorService)                                 │
│                                                              │
└──────────────────────────────────────────────────────────────┘
```

| 线程池 | 类型 | 大小 | 职责 |
|--------|------|------|------|
| Milo I/O | Milo 内置 `EventLoop` | 由 Milo 管理 | OPC UA 协议通信、订阅回调触发 |
| 回调分发 | `ThreadPoolExecutor` + 按 deviceId 分桶 | CPU 核数 * 2，可配置 | 接收 `OpcUaDeviceData`，异步回调 Listener |
| 轮询调度 | `ScheduledThreadPoolExecutor` | CPU 核数，可配置 | 管理所有设备轮询定时任务 |

### 2.2 回调分发 — 按设备分桶

```
subscribeCallback(OpcUaDeviceData)
        │
        ▼
  ┌─────────────────────────────────┐
  │  deviceId.hashCode() % N        │
  │  → 路由到 Bucket[K]             │
  └─────────────────────────────────┘
        │
        ▼
  Bucket[0]        Bucket[1]        ...        Bucket[N-1]
  ┌─────────┐     ┌─────────┐                 ┌─────────┐
  │ Queue   │     │ Queue   │                 │ Queue   │
  └────┬────┘     └────┬────┘                 └────┬────┘
       │               │                           │
       ▼               ▼                           ▼
  ThreadPoolExecutor (size = M)
```

- 同 deviceId 始终路由到同一 Bucket，保证同设备数据有序回调
- 队列满时 drop-oldest（丢弃最老消息），防止 OOM
- 线程池大小和队列容量均可 YAML 配置

### 2.3 轮询调度

- 全局 `ScheduledThreadPoolExecutor`，所有设备共享
- 每个轮询节点注册一个 `scheduleWithFixedDelay` 任务
- 设备删除时取消对应任务
- Change 3 动态配置变更时增删相应定时任务

## 3. 核心模块设计

### 3.1 MiloClientWrapper

```
┌──────────────────────────────────────┐
│          MiloClientWrapper            │
├──────────────────────────────────────┤
│ - client: OpcUaClient                │
│ - config: DeviceConfig               │
│ - state: ConnectionState             │
│ - reconnectBackoff: int (1s → 60s)   │
├──────────────────────────────────────┤
│ + connect(): CompletableFuture<Void> │
│ + disconnect(): void                 │
│ + getClient(): OpcUaClient           │
│ + getState(): ConnectionState        │
│ + onStateChange(Consumer<State>)     │
└──────────────────────────────────────┘
```

重连状态机：

```
  CONNECTED ──(断开)──▶ RECONNECTING ──(成功)──▶ CONNECTED
      ▲                    │
      │                    │(连续失败, 退避时间递增)
      │                    ▼
      │              RECONNECTING (等待 backoff ms 后重试)
      │                    │
      └────────────────────┘(成功)

  任意状态 ──(主动 disconnect)──▶ DISCONNECTED (不再重连)
```

### 3.2 ConnectionManager

```
┌────────────────────────────────────────────┐
│             ConnectionManager               │
├────────────────────────────────────────────┤
│  ConcurrentHashMap<deviceId, DeviceHandle>  │
├────────────────────────────────────────────┤
│ + startAll(configs): void                   │
│ + addDevice(config): DeviceHandle           │
│ + removeDevice(deviceId): void              │
│ + getState(deviceId): DeviceState           │
│ + getAllStates(): Map<String, DeviceState>  │
└────────────────────────────────────────────┘

DeviceHandle:
  - id: String
  - wrappers: List<MiloClientWrapper>  (连接池)
  - subscriptions: List<Subscription>
  - pollingTasks: List<ScheduledFuture<?>>
  - state: DeviceState (CONNECTED/DISCONNECTED/RECONNECTING)
```

### 3.3 SubscriptionManager

```
┌───────────────────────────────────────────┐
│          SubscriptionManager               │
├───────────────────────────────────────────┤
│ + createSubscription(wrapper, config)      │
│ + removeSubscription(subId)                │
│ + recreateAll(wrapper): void               │
└───────────────────────────────────────────┘
```

- 订阅回调线程只做数据转换（Milo Event → OpcUaDataPoint），不执行 Listener 回调
- 转换完成后将 `OpcUaDeviceData` 交给分发线程池
- 重连后通过 `recreateAll` 重建所有订阅组

### 3.4 ReadWriteHandler

- 全局 `ScheduledThreadPoolExecutor` 管理轮询
- 每个轮询节点：`scheduler.scheduleWithFixedDelay(readTask, 0, interval, TimeUnit.MILLISECONDS)`
- 同设备多个轮询节点在同一周期内读取的结果聚合为一次 `OpcUaDeviceData` 输出
- 写入操作通过 `OpcUaClient.writeValue()` 同步执行，校验数据类型

### 3.5 数据管道（端到端）

```
订阅路径:
  Milo Callback → convertTo(OpcUaDataPoint) → aggregateByDevice()
  → OpcUaDeviceData → Bucket[hash(deviceId)] → ThreadPool → DataListener.onDataReceived()

轮询路径:
  ScheduledExecutor → readNodes(deviceId) → convertTo(OpcUaDataPoint)[]
  → aggregateByDevice() → OpcUaDeviceData → Bucket → ThreadPool → DataListener

写入路径:
  OpcUaService.writeValue(deviceId, nodeId, value)
  → ConnectionManager.getClient(deviceId)
  → client.writeValue(nodeId, value) → WriteResult
```

## 4. 错误处理与回退

### 4.1 故障隔离

```
每设备独立：
  - 连接断开 → 标记 DISCONNECTED → 启动重连（不影响其他设备）
  - 订阅回调抛异常 → catch + log → 不中断其他订阅
  - 轮询读取失败 → catch + log → skip → 下一周期继续（不输出该节点）
  - 连接池耗尽 → 等待超时 → 返回 ConnectionUnavailableException
```

### 4.2 背压控制

- 分发 Bucket 队列容量可配置（默认 1024）
- 队列满时 drop-oldest，记录 WARN + metrics
- 不阻塞 Milo 订阅回调线程

## 5. Spring Boot 集成

```java
@Configuration
@EnableConfigurationProperties(OpcUaProperties.class)
public class OpcUaCoreAutoConfiguration {

    @Bean
    public OpcUaService opcUaService(/* deps */) { ... }

    @Bean
    public OpcUaHealthIndicator opcUaHealthIndicator(ConnectionManager cm) { ... }
}
```

- `OpcUaProperties` 绑定 `opcua.*` YAML 配置段
- `@ConditionalOnProperty` 控制自动启用
- DataListener 通过 `List<OpcUaDataListener>` 自动注入多播

## 6. 测试策略

| 层级 | 范围 | 工具 | 覆盖内容 |
|------|------|------|---------|
| 单元测试 | QualityEvaluator, DataMapper, 连接池 | JUnit 5 + Mockito | StatusCode 解析、数据映射正确性、池行为 |
| 集成测试 | MiloClientWrapper → SubscriptionManager | Milo Example Server | 真实连接、订阅创建、数据接收、JSON 输出 |
| 压力测试 | 100+ 模拟设备 | 虚拟 OPC UA 端点 | 线程池饱和度、队列积压、内存消耗 |
| 故障测试 | 断线重连 | 模拟网络故障 | 重连恢复、订阅重建、状态变更 |

### 集成测试关键场景

1. 启动 → 连接 Example Server → 创建订阅 → 等待数据 → 验证 JSON 结构
2. 修改 Example Server 节点值 → 验证订阅推送及时性
3. 写入节点值 → 读取验证 → 确认 WriteResult
4. 停止 Example Server → 验证重连行为 → 重启 Server → 验证订阅恢复

## 7. 依赖清单

| 依赖 | 版本 | 用途 |
|------|------|------|
| org.eclipse.milo:sdk-client | 0.6.x | OPC UA 客户端库 |
| org.springframework.boot:spring-boot-starter | 3.x | Spring Boot 核心 |
| org.springframework.boot:spring-boot-starter-actuator | 3.x | 健康检查端点 |
| org.springframework.boot:spring-boot-configuration-processor | 3.x | YAML 配置绑定 |
| com.fasterxml.jackson.core:jackson-databind | (Spring 管理) | JSON 序列化 |
| org.projectlombok:lombok | (optional) | 减少样板代码 |
