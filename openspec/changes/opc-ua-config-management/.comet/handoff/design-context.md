# Comet Design Handoff

- Change: opc-ua-config-management
- Phase: design
- Mode: compact
- Context hash: 70a2fbe1f5a4220395d77ed9879eaedae60891acba989bf5cb5b25cc7c30c921

Generated-by: comet-handoff.sh

OpenSpec remains the canonical capability spec. This handoff is a deterministic, source-traceable context pack, not an agent-authored summary.

## openspec/changes/opc-ua-config-management/proposal.md

- Source: openspec/changes/opc-ua-config-management/proposal.md
- Lines: 1-33
- SHA256: 6ba71dba7688a45bf6fa5b7ca1fd8c845362e4b36923ba27ada9f675574f0a0b

```md
## Why

Change 1 和 Change 2 实现了 OPC UA 数据采集和转发能力，但所有配置通过 YAML 文件静态管理，修改设备连接或转发规则必须重启服务。对于 100+ 设备规模的动态运维场景，重启带来的数据丢失窗口不可接受。Change 3 补齐 REST API 动态配置和标准化部署能力，使整个系统具备生产级运维水平。

## What Changes

- **NEW** REST API：设备连接 CRUD（增删改查）、转发规则 CRUD、系统状态查询
- **NEW** 运行时配置管理：通过 REST API 修改的配置实时反映到 Change 1 连接池和 Change 2 转发引擎，无需重启
- **NEW** 配置持久化：运行时修改持久化到配置存储（数据库或 YAML 文件）
- **NEW** Docker 部署：Dockerfile + docker compose，包含 OPC UA 服务 + Kafka + InfluxDB + Redis
- **NEW** 集群基础：Redis 共享 Session，多实例健康聚合
- **NEW**（第二期）REST API 认证：JWT 或 Basic Auth 保护管理接口

## Capabilities

### New Capabilities

- `device-api`: 设备连接 REST API（增删改查、连接状态查询）
- `forward-api`: 转发规则 REST API（增删改查、规则启停）
- `runtime-config`: 运行时配置动态热加载，API 修改实时生效
- `docker-deployment`: Docker 标准化部署（Dockerfile + docker compose）
- `cluster-support`: 集群基础支持（Redis Session 共享、健康聚合）

### Modified Capabilities

<!-- 不修改 Change 1/2 的 capability，对外暴露配置管理接口 -->

## Impact

- **依赖 Change 1 + Change 2**：通过 Spring Bean 注入动态控制 ConnectionManager 和 ForwardingEngine
- **新增依赖**：Spring Web（REST API）、Spring Session + Redis（集群）
- **部署变更**：新增 Dockerfile、docker-compose.yml
- **配置系统**：引入配置持久化层（数据库表或文件存储）
```

## openspec/changes/opc-ua-config-management/design.md

- Source: openspec/changes/opc-ua-config-management/design.md
- Lines: 1-101
- SHA256: 6a75c67ed76c204b3a4f98434d49b287f0c5e88a4f54a9e56f1d9c156ba5b07a

[TRUNCATED]

```md
## Context

Change 1（`opc-ua-core`）和 Change 2（`opc-ua-forward`）已在同进程中通过 Spring Bean 协作。Change 3 在其上添加 REST API 层和部署基础设施。

**约束条件**：
- Java 17 + Spring Boot 3.x
- 与 Change 1/2 同进程运行
- 第一期不做 API 认证，第二期追加
- Docker Compose 整合 Kafka + InfluxDB + Redis

## Goals / Non-Goals

**Goals:**
- REST API：设备 CRUD、转发规则 CRUD、系统状态查询
- 运行时热加载：API 修改实时作用于 ConnectionManager 和 ForwardingEngine
- Docker 部署：Dockerfile + docker compose 一键启动全栈
- 集群基础：Redis Session 共享 + 多实例健康聚合

**Non-Goals:**
- UI 管理界面
- 分布式配置中心集成
- 自动扩缩容、服务发现
- 第一期不做 REST API 认证

## Decisions

### D1: REST API 设计

```
POST   /api/devices              # 新增设备连接（开始采集）
DELETE /api/devices/{id}          # 移除设备连接（停止采集）
GET    /api/devices               # 查询所有设备及连接状态
GET    /api/devices/{id}          # 查询单个设备详情
PUT    /api/devices/{id}          # 修改设备配置（重连生效）

GET    /api/forward/rules         # 查询所有转发规则
POST   /api/forward/rules         # 新增转发规则（立即生效）
PUT    /api/forward/rules/{name}  # 修改转发规则（立即生效）
DELETE /api/forward/rules/{name}  # 删除转发规则（立即停止）
PATCH  /api/forward/rules/{name}/enable   # 启停规则
PATCH  /api/forward/rules/{name}/disable

GET    /api/health                # 聚合健康检查（同 /actuator/health）
GET    /api/status                # 系统状态（设备数、连接数、转发统计）
```

**理由**：标准 RESTful 资源设计，运维工具（Ansible、脚本）可轻松集成。

### D2: 运行时热加载机制

```
┌──────────┐    ┌──────────────────┐    ┌─────────────────┐
│ REST API │───▶│ ConfigService     │───▶│ ConnectionManager│
│ Controller│    │ (内存配置模型)     │    │ (设备增删改)      │
└──────────┘    │                  │    └─────────────────┘
                │ • 原子更新        │    ┌─────────────────┐
                │ • 持久化到文件     │───▶│ ForwardingEngine │
                │ • 发布变更事件     │    │ (规则增删改)      │
                └──────────────────┘    └─────────────────┘
```

- `ConfigService` 持有线程安全的配置模型副本
- API 修改后先持久化（写入 YAML 文件 + 内存），再发布 `ConfigChangeEvent`
- `ConnectionManager` 和 `ForwardingEngine` 监听事件，按变更类型执行增量更新（新增连接/移除连接/重载规则）

**备选方案**：
- 重启进程：简单但违反"不停机"需求
- OSGi 热加载：过度工程化

**选择理由**：事件驱动增量更新，最小化变更影响范围，不中断已有设备的正常运行。

### D3: 配置持久化

- 运行时配置存储为内存模型 + YAML 文件双写
- 服务启动时优先读取 YAML 文件，REST API 修改后回写 YAML
- 第一期不引入数据库依赖（简化部署），第二期可切换到 DB

### D4: Docker Compose 拓扑

```yaml
```

Full source: openspec/changes/opc-ua-config-management/design.md

## openspec/changes/opc-ua-config-management/tasks.md

- Source: openspec/changes/opc-ua-config-management/tasks.md
- Lines: 1-49
- SHA256: 57c399cc34b1b8dfe3c3703c7643125b9c7184a90713aab883ced8f8ff825eb1

```md
## 1. REST API 基础

- [ ] 1.1 添加 Spring Web 依赖，创建 `api` 包结构
- [ ] 1.2 实现统一响应格式（`ApiResponse<T>`）和全局异常处理
- [ ] 1.3 实现配置模型转换：API DTO ↔ 内部 DeviceConfig/ForwardRule 模型

## 2. 设备管理 API

- [ ] 2.1 实现 `DeviceController`：GET/POST/PUT/DELETE /api/devices 端点
- [ ] 2.2 实现设备状态查询：返回 connectionStatus、lastConnectedAt 等运行时信息
- [ ] 2.3 实现请求参数校验（endpoint URL 格式、必填字段）

## 3. 转发规则 API

- [ ] 3.1 实现 `ForwardRuleController`：GET/POST/PUT/DELETE /api/forward/rules 端点
- [ ] 3.2 实现规则启停：PATCH enable/disable 端点
- [ ] 3.3 实现规则唯一性校验：同 name 不可重复创建

## 4. 运行时配置热加载

- [ ] 4.1 实现 `ConfigService`：线程安全配置模型管理 + ConfigChangeEvent 发布
- [ ] 4.2 实现 `ConnectionManager` 监听 ConfigChangeEvent：增量新增/移除/修改设备连接
- [ ] 4.3 实现 `ForwardingEngine` 监听 ConfigChangeEvent：增量加载/移除/修改转发规则
- [ ] 4.4 实现扩展点接口，供 Change 1/2 注册配置变更监听器

## 5. 配置持久化

- [ ] 5.1 实现 YAML 配置读写工具：启动时加载 → API 修改后原子回写
- [ ] 5.2 实现启动恢复：从 YAML 文件加载上次运行时配置

## 6. Docker 部署

- [ ] 6.1 编写多阶段 Dockerfile（Maven 构建 + JRE 运行）
- [ ] 6.2 编写 docker-compose.yml（opcua-service + Kafka + InfluxDB + Redis）
- [ ] 6.3 实现 healthcheck 和依赖顺序（depends_on + health condition）
- [ ] 6.4 配置外部 YAML 挂载支持

## 7. 集群支持

- [ ] 7.1 添加 Spring Session + Redis 依赖，实现 Session 共享
- [ ] 7.2 实现 `/api/status` 端点：设备数、连接数、规则数、各 Sender 状态汇总
- [ ] 7.3 实现集群健康聚合：汇总所有设备连接状态

## 8. 集成测试

- [ ] 8.1 编写 DeviceController 集成测试（CRUD + 状态查询完整覆盖）
- [ ] 8.2 编写 ForwardRuleController 集成测试（CRUD + 启停）
- [ ] 8.3 编写热加载集成测试：API 新增设备后验证采集启动、删除后验证连接断开
- [ ] 8.4 编写 docker compose 启动验证（服务启动 + 健康检查）
```

## openspec/changes/opc-ua-config-management/specs/cluster-support/spec.md

- Source: openspec/changes/opc-ua-config-management/specs/cluster-support/spec.md
- Lines: 1-19
- SHA256: 757a70eec84aff8f4f54f35136b85bc6836a7472cf1f05662b85261b113d1df5

```md
## ADDED Requirements

### Requirement: Redis Session 共享
系统 SHALL 使用 Redis 共享 HTTP Session，支持多实例部署时 Session 一致性。

#### Scenario: Session 跨实例共享
- **WHEN** 多实例通过 Docker Compose 部署且共享 Redis
- **THEN** 在实例 A 创建的 Session 可在实例 B 读取

### Requirement: 集群健康聚合
系统 SHALL 提供 `/api/status` 端点返回系统级运行摘要。

#### Scenario: 系统状态查询
- **WHEN** GET /api/status
- **THEN** 响应包含：已配置设备总数、当前连接数、已配置转发规则数、各 Sender 状态（Kafka/InfluxDB/MQTT 是否连通）

#### Scenario: 健康聚合
- **WHEN** 多个设备部分断开
- **THEN** `/api/health` 返回包含各设备健康详情的聚合状态
```

## openspec/changes/opc-ua-config-management/specs/device-api/spec.md

- Source: openspec/changes/opc-ua-config-management/specs/device-api/spec.md
- Lines: 1-31
- SHA256: 9a52b6d0f3fd2476f4e0eaccae5470dd238e8b06fc2484007bf0967db0cf265d

```md
## ADDED Requirements

### Requirement: 设备连接 CRUD
系统 SHALL 通过 REST API 提供设备连接的增删改查操作。

#### Scenario: 新增设备
- **WHEN** POST /api/devices 携带有效设备配置 JSON
- **THEN** 系统持久化配置，立即启动该设备的连接和采集，返回 201 及设备信息

#### Scenario: 删除设备
- **WHEN** DELETE /api/devices/{id}
- **THEN** 系统断开该设备连接、清除订阅、关闭会话，移除配置，返回 204

#### Scenario: 修改设备配置
- **WHEN** PUT /api/devices/{id} 携带更新后的配置
- **THEN** 系统断开旧连接，以新配置重新建立连接和订阅，返回 200 及更新后信息

#### Scenario: 查询所有设备
- **WHEN** GET /api/devices
- **THEN** 系统返回所有已配置设备的列表及当前连接状态

#### Scenario: 查询单个设备
- **WHEN** GET /api/devices/{id}
- **THEN** 系统返回该设备详细配置及当前连接状态、订阅状态

### Requirement: 设备状态查询
系统 SHALL 在设备列表中返回实时连接状态。

#### Scenario: 连接状态字段
- **WHEN** 查询设备列表或详情
- **THEN** 响应中每个设备包含 `status` 字段（CONNECTED/DISCONNECTED/RECONNECTING）及 `lastConnectedAt` 时间戳
```

## openspec/changes/opc-ua-config-management/specs/docker-deployment/spec.md

- Source: openspec/changes/opc-ua-config-management/specs/docker-deployment/spec.md
- Lines: 1-26
- SHA256: 8c6e0f62c116ec32883131d99682d1c7b5ca228ab219ce297021e2d7b3f61286

```md
## ADDED Requirements

### Requirement: Docker 镜像构建
系统 SHALL 提供多阶段 Dockerfile，将 Spring Boot 应用构建为 Docker 镜像。

#### Scenario: 镜像构建成功
- **WHEN** 执行 `docker build -t opcua-service .`
- **THEN** 生成包含 Java 17 运行时和 Spring Boot jar 的镜像

### Requirement: Docker Compose 一键部署
系统 SHALL 提供 docker-compose.yml，包含全部依赖服务。

#### Scenario: 一键启动全栈
- **WHEN** 执行 `docker compose up -d`
- **THEN** 以下服务全部启动并健康：opcua-service、Kafka、InfluxDB、Redis

#### Scenario: 服务健康依赖顺序
- **WHEN** docker compose 启动
- **THEN** opcua-service 在 Kafka + InfluxDB + Redis 均已 healthy 后才启动

### Requirement: 配置文件挂载
系统 SHALL 支持通过 Docker volume 挂载外部 YAML 配置文件。

#### Scenario: 外部配置加载
- **WHEN** docker compose 中挂载了 `./config/application.yml:/app/config/application.yml`
- **THEN** 服务启动时加载挂载的配置文件
```

## openspec/changes/opc-ua-config-management/specs/forward-api/spec.md

- Source: openspec/changes/opc-ua-config-management/specs/forward-api/spec.md
- Lines: 1-31
- SHA256: 4bca0d3d1c288145c072d5cb7a1311ded4ab7389e7b04060c2b2be2fe18fcfed

```md
## ADDED Requirements

### Requirement: 转发规则 CRUD
系统 SHALL 通过 REST API 提供转发规则的增删改查操作。

#### Scenario: 新增转发规则
- **WHEN** POST /api/forward/rules 携带有效规则 JSON
- **THEN** 系统持久化规则，立即加载到 ForwardingEngine，匹配的数据开始按规则转发，返回 201

#### Scenario: 修改转发规则
- **WHEN** PUT /api/forward/rules/{name} 携带更新后的规则
- **THEN** 系统更新规则，ForwardingEngine 实时使用新规则匹配，返回 200

#### Scenario: 删除转发规则
- **WHEN** DELETE /api/forward/rules/{name}
- **THEN** 系统移除规则，停止对应转发，返回 204

#### Scenario: 查询所有规则
- **WHEN** GET /api/forward/rules
- **THEN** 系统返回所有转发规则列表及每条规则的 enabled 状态

### Requirement: 规则启停
系统 SHALL 支持通过 API 单独启停转发规则。

#### Scenario: 启用规则
- **WHEN** PATCH /api/forward/rules/{name}/enable
- **THEN** 规则立即生效，匹配的数据开始转发

#### Scenario: 停用规则
- **WHEN** PATCH /api/forward/rules/{name}/disable
- **THEN** 规则立即停止，匹配的数据不再转发，但规则配置保留
```

## openspec/changes/opc-ua-config-management/specs/runtime-config/spec.md

- Source: openspec/changes/opc-ua-config-management/specs/runtime-config/spec.md
- Lines: 1-27
- SHA256: f13a3b2409d0fbd64da8d9121043b208747ae255d9792c8794f17ef386796d5c

```md
## ADDED Requirements

### Requirement: 运行时配置热加载
系统 SHALL 支持 API 修改配置后实时生效，无需重启服务。

#### Scenario: 新增设备立即采集
- **WHEN** 通过 API 新增设备后
- **THEN** ConnectionManager 在 3 秒内启动该设备的连接和数据采集，不中断其他已有设备

#### Scenario: 新增规则立即转发
- **WHEN** 通过 API 新增转发规则后
- **THEN** ForwardingEngine 在 3 秒内加载新规则，匹配的数据立即按规则转发

#### Scenario: 删除设备不影响其他设备
- **WHEN** 通过 API 删除某设备
- **THEN** 仅该设备断开连接，其他设备正常运行不受影响

### Requirement: 配置持久化
系统 SHALL 在 API 修改后将配置持久化，确保服务重启后配置不丢失。

#### Scenario: 配置回写 YAML
- **WHEN** 通过 API 增删改设备或规则
- **THEN** 系统将变更持久化到 YAML 配置文件

#### Scenario: 启动恢复
- **WHEN** 服务重启
- **THEN** 系统从持久化的 YAML 文件加载上次运行时保存的完整配置
```

