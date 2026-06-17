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
