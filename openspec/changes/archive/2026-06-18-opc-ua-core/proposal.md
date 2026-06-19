## Why

工业物联网场景中，开发者在接入 OPC UA 设备时面临高学习成本和重复性工作：需要理解 OPC UA 协议细节、管理连接生命周期、处理复杂的数据交互模式。本项目构建一个开箱即用的 OPC UA 核心采集层，将协议复杂性封装为简洁的配置驱动抽象，让开发者聚焦在数据消费而非协议集成上。

## What Changes

- **NEW** OPC UA 设备连接管理：基于 YAML 配置驱动的设备发现、会话管理、连接池，支持 100+ 设备规模
- **NEW** 多模式数据采集：支持订阅（Subscription）、轮询读取、写入控制三种数据交互模式
- **NEW** 数据质量标记：自动读取 OPC UA StatusCode，标记每条数据质量（Good/Bad/Uncertain）到统一 JSON
- **NEW** 数据映射引擎：NodeId → 业务友好名称 → 统一 JSON 结构的可配置映射
- **NEW** 安全认证：支持证书、用户名/密码、加密模式等 OPC UA 标准安全机制
- **NEW** 健康检查与自动恢复：提供 `/health` 端点，连接断开自动重连，支持连接池监控

## Capabilities

### New Capabilities

- `device-connection`: OPC UA 设备连接管理，包括设备发现、会话管理、连接池、安全认证
- `data-collection`: 多模式数据采集，包括订阅推送、轮询读取、写入控制
- `data-quality`: 数据质量标记，基于 OPC UA StatusCode 评估每条数据质量
- `data-mapping`: NodeId 到业务友好名称的映射及统一 JSON 格式化输出
- `health-check`: 健康检查与自动恢复机制

### Modified Capabilities

<!-- 无现有 capability 需要修改 -->

## Impact

- **依赖**：Eclipse Milo（OPC UA 客户端库）
- **框架**：Spring Boot 3.x, Java 17
- **配置系统**：新增 YAML 配置结构定义 OPC UA 端点、节点、采集策略
- **API 接口**：提供内部 Java API 供下游模块消费统一 JSON 数据
- **部署**：作为 Spring Boot 服务模块运行，后续由 Change 3 包装为 Docker 容器
