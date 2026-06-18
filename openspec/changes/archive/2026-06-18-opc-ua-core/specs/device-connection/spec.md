## ADDED Requirements

### Requirement: 设备发现与连接
系统 SHALL 通过 YAML 配置文件定义 OPC UA 设备端点，在服务启动时自动建立连接并发现设备。

#### Scenario: 启动时自动连接
- **WHEN** 服务启动且 YAML 配置中存在有效设备端点列表
- **THEN** 系统依次连接每个端点，并记录连接成功/失败状态到日志

#### Scenario: 设备端点不可达
- **WHEN** 服务启动时某设备端点不可达
- **THEN** 系统记录连接失败日志，标记该设备为 DISCONNECTED，并启动自动重连流程

### Requirement: 连接池管理
系统 SHALL 为每个 OPC UA 端点维护一个有界连接池，最大连接数可配置，默认值为 3。

#### Scenario: 连接池复用
- **WHEN** 多个订阅或读写操作请求同时发生
- **THEN** 系统从连接池中分配可用连接，等待超时后连接释放回池复用

#### Scenario: 连接池耗尽
- **WHEN** 所有连接均在占用中且达到最大连接数
- **THEN** 新请求在配置的超时时间内等待，超时后返回连接不可用错误

#### Scenario: 空闲连接回收
- **WHEN** 连接空闲时间超过配置的 idle-timeout
- **THEN** 系统关闭该连接并从连接池中移除

### Requirement: OPC UA 安全认证
系统 SHALL 支持 OPC UA 标准安全机制，包括证书认证和用户名/密码认证。

#### Scenario: 证书认证
- **WHEN** 配置中指定了客户端证书路径和加密策略（如 Basic256Sha256）
- **THEN** 系统使用指定证书与 OPC UA 服务器建立安全通道和会话

#### Scenario: 用户名密码认证
- **WHEN** 配置中指定了 username 和 password
- **THEN** 系统在建立会话后执行用户身份认证

#### Scenario: 无安全模式
- **WHEN** 配置中未指定安全策略（policy: None）
- **THEN** 系统以匿名模式连接 OPC UA 服务器

### Requirement: 会话生命周期管理
系统 SHALL 管理每个 OPC UA 会话的完整生命周期，包括创建、保持活跃、超时处理和销毁。

#### Scenario: 会话创建
- **WHEN** 设备连接建立成功
- **THEN** 系统创建 OPC UA 会话并设置会话超时时间（可配置，默认 600s）

#### Scenario: 会话保持
- **WHEN** 会话处于活跃状态
- **THEN** 系统定期发送 keep-alive 请求，确保会话不过期

#### Scenario: 会话超时恢复
- **WHEN** 会话因网络中断等原因超时失效
- **THEN** 系统销毁旧会话，重新建立连接并创建新会话
