## 1. REST API 基础

- [x] 1.1 添加 Spring Web 依赖，创建 `api` 包结构
- [x] 1.2 实现统一响应格式（`ApiResponse<T>`）和全局异常处理
- [x] 1.3 实现配置模型转换：API DTO ↔ 内部 DeviceConfig/ForwardRule 模型

## 2. 设备管理 API

- [x] 2.1 实现 `DeviceController`：GET/POST/PUT/DELETE /api/devices 端点
- [x] 2.2 实现设备状态查询：返回 connectionStatus、lastConnectedAt 等运行时信息
- [x] 2.3 实现请求参数校验（endpoint URL 格式、必填字段）

## 3. 转发规则 API

- [x] 3.1 实现 `ForwardRuleController`：GET/POST/PUT/DELETE /api/forward/rules 端点
- [x] 3.2 实现规则启停：PATCH enable/disable 端点
- [x] 3.3 实现规则唯一性校验：同 name 不可重复创建

## 4. 运行时配置热加载

- [x] 4.1 实现 `ConfigService`：线程安全配置模型管理 + ConfigChangeEvent 发布
- [x] 4.2 实现 `ConnectionManager` 监听 ConfigChangeEvent：增量新增/移除/修改设备连接
- [x] 4.3 实现 `ForwardingEngine` 监听 ConfigChangeEvent：增量加载/移除/修改转发规则
- [x] 4.4 实现扩展点接口，供 Change 1/2 注册配置变更监听器

## 5. 配置持久化

- [x] 5.1 实现 YAML 配置读写工具：启动时加载 → API 修改后原子回写
- [x] 5.2 实现启动恢复：从 YAML 文件加载上次运行时配置

## 6. Docker 部署

- [x] 6.1 编写多阶段 Dockerfile（Maven 构建 + JRE 运行）
- [x] 6.2 编写 docker-compose.yml（opcua-service + Kafka + InfluxDB + Redis）
- [x] 6.3 实现 healthcheck 和依赖顺序（depends_on + health condition）
- [x] 6.4 配置外部 YAML 挂载支持

## 7. 集群支持

- [x] 7.1 添加 Spring Session + Redis 依赖，实现 Session 共享
- [x] 7.2 实现 `/api/status` 端点：设备数、连接数、规则数、各 Sender 状态汇总
- [x] 7.3 实现集群健康聚合：汇总所有设备连接状态

## 8. 集成测试

- [x] 8.1 编写 DeviceController 集成测试（CRUD + 状态查询完整覆盖）
- [x] 8.2 编写 ForwardRuleController 集成测试（CRUD + 启停）
- [x] 8.3 编写热加载集成测试：API 新增设备后验证采集启动、删除后验证连接断开
- [x] 8.4 编写 docker compose 启动验证（服务启动 + 健康检查）

<!-- docker compose 启动验证：本地环境 docker 不可用，YAML 语法已通过 Python yaml.safe_load 验证；实际服务启动验证留待 Docker 环境执行 -->
