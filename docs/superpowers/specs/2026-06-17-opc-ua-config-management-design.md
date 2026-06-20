---
comet_change: opc-ua-config-management
role: technical-design
canonical_spec: openspec
status: final
---

# OPC UA 配置管理 — 技术设计文档

> 创建日期：2026-06-17
> 关联 Change：opc-ua-config-management
> 依赖：opc-ua-core（Change 1）、opc-ua-forward（Change 2）

## 1. 概述

Change 1 和 Change 2 实现了 OPC UA 数据采集和转发能力，但所有配置通过 YAML 文件静态管理，修改设备连接或转发规则必须重启服务。Change 3 补齐 REST API 动态配置和标准化部署能力，使系统具备生产级运维水平。

## 2. 架构概览

```
┌─────────────────────────────────────────────────────────┐
│                    Docker Compose                        │
│  ┌───────────┐  ┌────────┐  ┌──────────┐  ┌─────────┐  │
│  │ opcua-    │  │ Kafka  │  │ InfluxDB │  │  Redis  │  │
│  │ service   │  │        │  │          │  │         │  │
│  │           │  │        │  │          │  │         │  │
│  │ ┌───────┐ │  │        │  │          │  │ Session │  │
│  │ │REST   │ │  │        │  │          │  │ 共享    │  │
│  │ │API    │ │  │        │  │          │  │         │  │
│  │ └─┬─────┘ │  │        │  │          │  │         │  │
│  │   │       │  │        │  │          │  │         │  │
│  │ ┌─▼─────┐ │  └────────┘  └──────────┘  └─────────┘  │
│  │ │Config │ │                                           │
│  │ │Service│ │                                           │
│  │ └─┬─────┘ │                                           │
│  │   │       │                                           │
│  │ ┌─▼──────┐│  ┌──────────────┐                        │
│  │ │Config  ││  │Connection    │  ← Change 1             │
│  │ │Change  │├─►│Manager       │                         │
│  │ │Event   ││  └──────────────┘                        │
│  │ │        ││  ┌──────────────┐                        │
│  │ │        │├─►│Forwarding    │  ← Change 2             │
│  │ │        ││  │Engine        │                         │
│  │ └────────┘│  └──────────────┘                        │
│  │           │                                           │
│  │ ┌────────┐│                                           │
│  │ │YAML    ││  配置持久化（文件系统）                      │
│  │ │Config  ││                                           │
│  │ │Files   ││                                           │
│  │ └────────┘│                                           │
│  └───────────┘                                           │
└─────────────────────────────────────────────────────────┘
```

## 3. 核心设计决策

### D1: REST API 设计

标准 RESTful 资源设计，设备管理和转发规则管理两套 API + 系统状态端点。

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

GET    /api/health                # 聚合健康检查
GET    /api/status                # 系统状态摘要
```

**理由**：标准 RESTful 设计，运维工具（Ansible、脚本）可轻松集成。

### D2: 运行时热加载机制

事件驱动增量更新，最小化变更影响范围：

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
- `ConnectionManager` 和 `ForwardingEngine` 监听事件，按变更类型执行增量更新

**备选方案**：重启进程 → 违反"不停机"需求；OSGi 热加载 → 过度工程化。

### D3: 配置持久化

- 运行时配置存储为内存模型 + YAML 文件双写
- 服务启动时优先读取 YAML 文件，REST API 修改后回写 YAML
- 第一期不引入数据库依赖（简化部署），第二期可切换到 DB

### D4: Docker Compose 拓扑

```yaml
services:
  opcua-service:    # 本应用（Java 17 + Spring Boot）
  kafka:            # 消息队列
  influxdb:         # InfluxDB 2.x 时序数据库
  redis:            # Redis 7（集群 Session 共享）
```

### D5: 集群基础

- Spring Session Redis 共享 HTTP Session
- `/api/status` 返回系统级运行摘要
- 不实现服务发现（由 Docker Compose / K8s Service 提供）

## 4. 数据模型

### 统一响应格式

```java
public class ApiResponse<T> {
    private int code;        // 业务状态码
    private String message;  // 提示信息
    private T data;          // 响应数据
    private long timestamp;  // 响应时间戳
}
```

### 设备 DTO

```java
public class DeviceConfigDTO {
    private String id;
    private String name;
    private String endpointUrl;
    private String securityPolicy;
    private Duration timeout;
    private List<NodeConfig> nodes;
    private DeviceStatus status;       // CONNECTED/DISCONNECTED/RECONNECTING
    private Instant lastConnectedAt;
}
```

### 转发规则 DTO

```java
public class ForwardRuleDTO {
    private String name;
    private String type;        // KAFKA/INFLUXDB/MQTT/HTTP
    private boolean enabled;
    private Map<String, Object> config;  // type-specific config
}
```

## 5. 配置变更事件模型

```java
public class ConfigChangeEvent {
    private ChangeType type;    // DEVICE_ADDED/DEVICE_REMOVED/DEVICE_UPDATED/
                                // RULE_ADDED/RULE_REMOVED/RULE_UPDATED/RULE_TOGGLED
    private String targetId;    // 设备 ID 或规则 name
    private Object payload;     // 变更后的配置对象
}
```

## 6. 风险与缓解

| 风险 | 缓解措施 |
|------|---------|
| YAML 文件并发写入安全 | 原子写入（写临时文件 + rename） |
| 集群多实例同时修改同一配置 | 第一期不处理分布式锁，运维约束单实例管理 |
| 热加载与当前采集操作竞态 | ConfigService 内部使用 CopyOnWrite 或 ReadWriteLock |

## 7. 能力清单

| 能力 | 类型 | 说明 |
|------|------|------|
| device-api | NEW | 设备连接 REST API（CRUD + 状态查询） |
| forward-api | NEW | 转发规则 REST API（CRUD + 启停） |
| runtime-config | NEW | 运行时配置热加载 + 持久化 |
| docker-deployment | NEW | Dockerfile + docker compose 一键部署 |
| cluster-support | NEW | Redis Session 共享 + 健康聚合 |

## 8. 非目标

- UI 管理界面
- 分布式配置中心集成
- 自动扩缩容、服务发现
- 第一期不做 REST API 认证（第二期追加）