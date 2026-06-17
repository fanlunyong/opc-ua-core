---
change: opc-ua-core
design-doc: docs/superpowers/specs/2026-06-17-opc-ua-core-technical-design.md
base-ref: ea5c42214f68e0b67fcc75e7bd705a3601cb8ed6
---

# OPC UA 核心采集层 — 实施计划

> **对于自动化执行者：** 使用 subagent-driven-development（推荐）或 executing-plans 按任务顺序实施。

**目标:** 构建 Java 17 + Spring Boot 3 的 OPC UA 核心采集层，封装 Eclipse Milo，YAML 配置驱动。

**技术栈:** Java 17, Spring Boot 3.2, Eclipse Milo 0.6.14, Maven, JUnit 5

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

---

### Task 1: 项目骨架搭建

**Files:** pom.xml, application.yml, 启动类, 全部 model 类, OpcUaProperties

- [ ] **Step 1:** 创建 pom.xml — Spring Boot 3.2 parent, Java 17, milo 0.6.14, actuator, jackson, test deps
- [ ] **Step 2:** 创建 OpcUaCoreApplication — `@SpringBootApplication` main class
- [ ] **Step 3:** 创建 application.yml — `opcua.enabled: true`, dispatch config, devices: []
- [ ] **Step 4:** 创建 OpcUaProperties — `@ConfigurationProperties("opcua")` 绑定 enabled + DispatchConfig + List<DeviceConfig>
- [ ] **Step 5:** 创建全部 12 个 model 类 (DeviceConfig/SecurityConfig/NodeConfig/SubscriptionGroupConfig/PollingNodeConfig/Quality/ConnectionState/OpcUaDataPoint/OpcUaDeviceData/DeviceHandle/DeviceState)
- [ ] **Step 6:** 验证 `mvn compile -q` BUILD SUCCESS
- [ ] **Step 7:** 提交 `feat: add project skeleton with data models and YAML config binding`

---

### Task 2: MiloClientWrapper

**Files:** `src/main/java/com/opcua/wrapper/MiloClientWrapper.java`

- [ ] **Step 1:** 实现 MiloClientWrapper — connect()/disconnect(), buildClient() with security (UsernameProvider/AnonymousProvider), 指数退避重连 (1s→60s), ConnectionState 回调机制
- [ ] **Step 2:** 验证 `mvn compile -q` BUILD SUCCESS
- [ ] **Step 3:** 提交 `feat: implement MiloClientWrapper with exponential backoff reconnect`

---

### Task 3: ConnectionManager

**Files:** `src/main/java/com/opcua/core/ConnectionManager.java`

- [ ] **Step 1:** 实现 ConnectionManager — ConcurrentHashMap<id, DeviceHandle>, startAll/addDevice/removeDevice/getClient/getState/getAllStates/shutdown, 有界连接池(default 3), 设备隔离
- [ ] **Step 2:** 验证 `mvn compile -q` BUILD SUCCESS
- [ ] **Step 3:** 提交 `feat: implement ConnectionManager with bounded connection pool`

---

### Task 4: DataDispatchEngine + SubscriptionManager

**Files:** `DataDispatchEngine.java`, `SubscriptionManager.java`

- [ ] **Step 1:** 实现 DataDispatchEngine — Bucket[] + LinkedBlockingQueue, deviceId hashCode 路由, drain 线程多播回调, drop-oldest 背压
- [ ] **Step 2:** 实现 SubscriptionManager — createSubscriptions(client, config.subscriptions), handleDataChange (DataValue → OpcUaDataPoint → OpcUaDeviceData → dispatch), recreateAll
- [ ] **Step 3:** 验证 `mvn compile -q` BUILD SUCCESS
- [ ] **Step 4:** 提交 `feat: implement SubscriptionManager and bucket-based async dispatch engine`

---

### Task 5: QualityEvaluator + DataMapper + ReadWriteHandler

**Files:** `QualityEvaluator.java`, `DataMapper.java`, `ReadWriteHandler.java`, `WriteResult.java`

- [ ] **Step 1:** QualityEvaluator — StatusCode.isGood()→Good, isBad()→Bad, else→Uncertain; logIfNeeded
- [ ] **Step 2:** DataMapper — displayName 已配置则用, 否则从 nodeId 提取标识符
- [ ] **Step 3:** ReadWriteHandler — startPolling (ScheduledThreadPoolExecutor scheduleWithFixedDelay), pollNode (readValue → quality → map → dispatch), writeValue→WriteResult, 异常容错
- [ ] **Step 4:** 验证 `mvn compile -q` BUILD SUCCESS
- [ ] **Step 5:** 提交 `feat: implement QualityEvaluator, DataMapper, and ReadWriteHandler`

---

### Task 6: OpcUaDataListener + OpcUaService

**Files:** `OpcUaDataListener.java`, `OpcUaService.java`

- [ ] **Step 1:** OpcUaDataListener — `@FunctionalInterface void onDataReceived(OpcUaDeviceData)`
- [ ] **Step 2:** OpcUaService — 组合 ConnectionManager + DataDispatchEngine, start/registerListener/writeValue/getDeviceStates/shutdown, 暴露 getConnectionManager()
- [ ] **Step 3:** 验证 `mvn compile -q` BUILD SUCCESS
- [ ] **Step 4:** 提交 `feat: implement OpcUaService unified API and DataListener interface`

---

### Task 7: 健康检查 + 自动配置

**Files:** `OpcUaHealthIndicator.java`, `OpcUaCoreAutoConfiguration.java`

- [ ] **Step 1:** OpcUaHealthIndicator — 实现 HealthIndicator, 设备全 CONNECTED→UP, 否则→DOWN
- [ ] **Step 2:** OpcUaCoreAutoConfiguration — `@ConditionalOnProperty("opcua.enabled")`, Bean: OpcUaService + OpcUaHealthIndicator
- [ ] **Step 3:** 验证 `mvn compile -q` BUILD SUCCESS
- [ ] **Step 4:** 提交 `feat: implement health check indicator and auto-configuration`

---

### Task 8: 单元测试

**Files:** `TestConstants.java`, `QualityEvaluatorTest.java`, `DataMapperTest.java`, `DataDispatchEngineTest.java`

- [ ] **Step 1:** QualityEvaluatorTest — test Good/Bad/Uncertain/null StatusCode
- [ ] **Step 2:** DataMapperTest — test configured displayName, auto-extract
- [ ] **Step 3:** DataDispatchEngineTest — CountDownLatch 验证 dispatch→listener 链路
- [ ] **Step 4:** 运行 `mvn test` (~7 tests pass)
- [ ] **Step 5:** 提交 `test: add unit tests for QualityEvaluator, DataMapper, and DataDispatchEngine`

---

### Task 9: 集成测试

**Files:** `MiloServerRunner.java`, `OpcUaIntegrationTest.java`

- [ ] **Step 1:** MiloServerRunner — start/stop ExampleServer
- [ ] **Step 2:** OpcUaIntegrationTest — connect + subscription data receive + write + states
- [ ] **Step 3:** 运行 `mvn test` (all pass)
- [ ] **Step 4:** 提交 `test: add integration tests with embedded Milo Example Server`

---

### Task 10: 最终验证

- [ ] **Step 1:** `mvn clean test` — 全部测试通过
- [ ] **Step 2:** `mvn clean package -DskipTests` — BUILD SUCCESS
- [ ] **Step 3:** 勾选 tasks.md 全部任务
- [ ] **Step 4:** 提交 `chore: finalize opc-ua-core implementation`
