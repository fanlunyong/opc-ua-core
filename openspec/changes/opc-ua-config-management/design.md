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
services:
  opcua-service:    # 本应用（Java 17 + Spring Boot）
  kafka:            # Confluent Kafka 或 bitnami/kafka
  influxdb:         # InfluxDB 2.x
  redis:            # Redis 7（集群 Session 共享）
```

### D5: 集群基础

- Spring Session Redis 共享 HTTP Session
- `/api/health` 聚合本实例所有设备健康状态
- 不实现服务发现（由 Docker Compose / K8s Service 提供）

## Risks / Trade-offs

- **[R] YAML 文件写的并发安全** → 文件写入加锁，或使用原子写入（写临时文件 + rename）
- **[T] 集群下多实例同时修改同一设备配置** → 第一期不处理分布式锁，建议运维约束单实例管理

## Open Questions

- 第二期 REST API 认证方案（JWT vs OAuth2 vs Basic Auth）
