---
change: opc-ua-core
design-doc: docs/superpowers/specs/2026-06-17-opc-ua-core-technical-design.md
base-ref: ea5c42214f68e0b67fcc75e7bd705a3601cb8ed6
archived-with: 2026-06-18-opc-ua-core
---

# OPC UA 核心采集层 — 实施计划

> **对于自动化执行者：** 使用 subagent-driven-development（推荐）或 executing-plans 按任务顺序实施。

**目标:** 构建 Java 17 + Spring Boot 3 的 OPC UA 核心采集层，封装 Eclipse Milo，YAML 配置驱动。

**技术栈:** Java 17, Spring Boot 3.2, Eclipse Milo 0.6.14, Maven, JUnit 5

archived-with: 2026-06-18-opc-ua-core
---

## 文件结构

```
src/main/java/com/opcua/
├── OpcUaCoreApplication.java
├── model/   (DeviceConfig, NodeConfig, SecurityConfig, SubscriptionGroupConfig,
│             PollingNodeConfig, OpcUaDataPoint, OpcUaDeviceData, DeviceHandle,
│             DeviceState, ConnectionState, Quality)
├── config/  (OpcUaProperties, OpcUaCoreAutoConfiguration)
├── wrapper/ (MiloClientWrapper)
├── core/    (ConnectionManager, SubscriptionManager, ReadWriteHandler,
│             DataMapper, QualityEvaluator, DataDispatchEngine)
├── api/     (OpcUaDataListener, OpcUaService, WriteResult)
└── health/  (OpcUaHealthIndicator)

src/test/java/com/opcua/
├── TestConstants.java
├── unit/   (QualityEvaluatorTest, DataMapperTest, DataDispatchEngineTest)
└── integration/  (MiloServerRunner, OpcUaIntegrationTest)
```

archived-with: 2026-06-18-opc-ua-core
---

### Task 1: 项目骨架搭建

**Files:** pom.xml, application.yml, 启动类, 全部 model 类, OpcUaProperties

- [x] **Step 1:** 创建 pom.xml — Spring Boot 3.2 parent, Java 17, milo 0.6.14, actuator, jackson, test deps
- [x] **Step 2:** 创建 OpcUaCoreApplication — `@SpringBootApplication` main class
- [x] **Step 3:** 创建 application.yml — `opcua.enabled: true`, dispatch config, devices: []
- [x] **Step 4:** 创建 OpcUaProperties — `@ConfigurationProperties("opcua")` 绑定 enabled + DispatchConfig + List<DeviceConfig>
- [x] **Step 5:** 创建全部 12 个 model 类 (DeviceConfig/SecurityConfig/NodeConfig/SubscriptionGroupConfig/PollingNodeConfig/Quality/ConnectionState/OpcUaDataPoint/OpcUaDeviceData/DeviceHandle/DeviceState)
- [x] **Step 6:** 验证 `mvn compile -q` BUILD SUCCESS
- [x] **Step 7:** 提交 `feat: add project skeleton with data models and YAML config binding`

archived-with: 2026-06-18-opc-ua-core
---

### Task 2: MiloClientWrapper

**Files:** `src/main/java/com/opcua/wrapper/MiloClientWrapper.java`

- [x] **Step 1:** 实现 MiloClientWrapper — connect()/disconnect(), buildClient() with security (UsernameProvider/AnonymousProvider), 指数退避重连 (1s→60s), ConnectionState 回调机制
- [x] **Step 2:** 验证 `mvn compile -q` BUILD SUCCESS
- [x] **Step 3:** 提交 `feat: implement MiloClientWrapper with exponential backoff reconnect`

archived-with: 2026-06-18-opc-ua-core
---

### Task 3: ConnectionManager

**Files:** `src/main/java/com/opcua/core/ConnectionManager.java`

- [x] **Step 1:** 实现 ConnectionManager — ConcurrentHashMap<id, DeviceHandle>, startAll/addDevice/removeDevice/getClient/getState/getAllStates/shutdown, 有界连接池(default 3), 设备隔离
- [x] **Step 2:** 验证 `mvn compile -q` BUILD SUCCESS
- [x] **Step 3:** 提交 `feat: implement ConnectionManager with bounded connection pool`

archived-with: 2026-06-18-opc-ua-core
---

### Task 4: DataDispatchEngine + SubscriptionManager

**Files:** `DataDispatchEngine.java`, `SubscriptionManager.java`

- [x] **Step 1:** 实现 DataDispatchEngine — Bucket[] + LinkedBlockingQueue, deviceId hashCode 路由, drain 线程多播回调, drop-oldest 背压
- [x] **Step 2:** 实现 SubscriptionManager — createSubscriptions(client, config.subscriptions), handleDataChange (DataValue → OpcUaDataPoint → OpcUaDeviceData → dispatch), recreateAll
- [x] **Step 3:** 验证 `mvn compile -q` BUILD SUCCESS
- [x] **Step 4:** 提交 `feat: implement SubscriptionManager and bucket-based async dispatch engine`（实际 commit 信息为 `feat: implement DataDispatchEngine and SubscriptionManager`，语义等价；已被 round‑1 spec review 接受）

archived-with: 2026-06-18-opc-ua-core
---

### Task 5: QualityEvaluator + DataMapper + ReadWriteHandler

**Files:** `QualityEvaluator.java`, `DataMapper.java`, `ReadWriteHandler.java`, `WriteResult.java`

- [x] **Step 1:** QualityEvaluator — StatusCode.isGood()→Good, isBad()→Bad, else→Uncertain; logIfNeeded
- [x] **Step 2:** DataMapper — displayName 已配置则用, 否则从 nodeId 提取标识符
- [x] **Step 3:** ReadWriteHandler — startPolling (ScheduledThreadPoolExecutor scheduleWithFixedDelay), pollNode (readValue → quality → map → dispatch), writeValue→WriteResult, 异常容错
- [x] **Step 4:** 验证 `mvn compile -q` BUILD SUCCESS
- [x] **Step 5:** 提交 `feat: implement QualityEvaluator, DataMapper, and ReadWriteHandler`

archived-with: 2026-06-18-opc-ua-core
---

### Task 6: OpcUaDataListener + OpcUaService

**Files:** `OpcUaDataListener.java`, `OpcUaService.java`

- [x] **Step 1:** OpcUaDataListener — `@FunctionalInterface void onDataReceived(OpcUaDeviceData)`（实际在 Task 4 commit d774090 中提前创建，签名一致；round‑1 review 接受 prefetch）
- [x] **Step 2:** OpcUaService — 组合 ConnectionManager + DataDispatchEngine, start/registerListener/writeValue/getDeviceStates/shutdown, 暴露 getConnectionManager()
- [x] **Step 3:** 验证 `mvn compile -q` BUILD SUCCESS
- [x] **Step 4:** 提交 `feat: implement OpcUaService unified API and DataListener interface`

archived-with: 2026-06-18-opc-ua-core
---

### Task 7: 健康检查 + 自动配置

**Files:** `OpcUaHealthIndicator.java`, `OpcUaCoreAutoConfiguration.java`

- [x] **Step 1:** OpcUaHealthIndicator — 实现 HealthIndicator, 设备全 CONNECTED→UP, 否则→DOWN
- [x] **Step 2:** OpcUaCoreAutoConfiguration — `@ConditionalOnProperty("opcua.enabled")`, Bean: OpcUaService + OpcUaHealthIndicator
- [x] **Step 3:** 验证 `mvn compile -q` BUILD SUCCESS
- [x] **Step 4:** 提交 `feat: implement health check indicator and auto-configuration`

archived-with: 2026-06-18-opc-ua-core
---

### Task 8: 单元测试

**Files:** `TestConstants.java`, `QualityEvaluatorTest.java`, `DataMapperTest.java`, `DataDispatchEngineTest.java`

- [x] **Step 1:** QualityEvaluatorTest — test Good/Bad/Uncertain/null StatusCode
- [x] **Step 2:** DataMapperTest — test configured displayName, auto-extract
- [x] **Step 3:** DataDispatchEngineTest — CountDownLatch 验证 dispatch→listener 链路
- [x] **Step 4:** 运行 `mvn test` (~7 tests pass) — 实际 133/133 全部通过（覆盖范围超出原计划）
- [x] **Step 5:** 提交 `test: add unit tests for QualityEvaluator, DataMapper, and DataDispatchEngine`（实际通过 Task 4-7 commits 中的 TDD 流程逐步落地，等价交付）

archived-with: 2026-06-18-opc-ua-core
---

### Task 9: 集成测试 — MOVED to follow-up change `opc-ua-core-hardening`

**Files:** `MiloServerRunner.java`, `OpcUaIntegrationTest.java`

> 此 task 整体迁移至 `openspec/changes/opc-ua-core-hardening/` §1-§4。
> 原因：需引入 `org.eclipse.milo:sdk-server` 测试依赖与 Example Server 启停基础设施，
> 范围超出本 change "核心层实现" 边界。详见 hardening change 的 proposal.md。

~~Step 1: MiloServerRunner — start/stop ExampleServer~~ → hardening §1.2
~~Step 2: OpcUaIntegrationTest — connect + subscription data receive + write + states~~ → hardening §2
~~Step 3: 运行 `mvn test` (all pass)~~ → hardening §7.1
~~Step 4: 提交 `test: add integration tests with embedded Milo Example Server`~~ → hardening §2.1

archived-with: 2026-06-18-opc-ua-core
---

### Task 10: 最终验证

- [x] **Step 1:** `mvn clean test` — 全部测试通过（133/133 PASS）
- [x] **Step 2:** `mvn clean package -DskipTests` — BUILD SUCCESS（产出 `opc-ua-core-0.1.0-SNAPSHOT.jar`）
- [x] **Step 3:** 勾选 tasks.md 全部任务（26/26 checked，9.2/10.2-10.4 已 split 至 hardening change）
- [x] **Step 4:** 提交 `chore: finalize opc-ua-core implementation`
