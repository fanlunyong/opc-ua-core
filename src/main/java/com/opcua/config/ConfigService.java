package com.opcua.config;

import com.opcua.forward.config.ForwardRule;
import com.opcua.forward.config.ForwardTarget;
import com.opcua.model.DeviceConfig;
import com.opcua.model.DeviceState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class ConfigService {

    private static final Logger logger = LoggerFactory.getLogger(ConfigService.class);

    private final Map<String, DeviceConfig> devices = new ConcurrentHashMap<>();
    private final Map<String, ForwardRule> rules = new ConcurrentHashMap<>();
    private final List<ConfigChangeListener> listeners = new CopyOnWriteArrayList<>();

    private com.opcua.core.ConnectionManager connectionManager;
    private ConfigPersistenceService persistenceService;

    public void setConnectionManager(com.opcua.core.ConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    public void setPersistenceService(ConfigPersistenceService persistenceService) {
        this.persistenceService = persistenceService;
    }

    /**
     * 从持久化文件恢复配置（启动时调用）。
     * 直接 put 到 map，不 fire 事件。恢复后调用 syncToListeners() 同步到运行时组件。
     */
    public void loadFromPersistence() {
        if (persistenceService == null) return;
        List<DeviceConfig> savedDevices = persistenceService.loadDevices();
        List<ForwardRule> savedRules = persistenceService.loadRules();
        for (DeviceConfig dc : savedDevices) {
            devices.put(dc.getDeviceId(), dc);
        }
        for (ForwardRule rule : savedRules) {
            rules.put(rule.getName(), rule);
        }
        logger.info("Loaded {} devices and {} rules from persistence", savedDevices.size(), savedRules.size());
    }

    /**
     * 将当前配置同步到所有已注册的 listeners（启动恢复后调用）。
     * 对每条设备 fire DEVICE_ADDED、每条规则 fire RULE_ADDED。
     * listeners 负责重复检查，避免与 YAML 初始配置重复。
     */
    public void syncToListeners() {
        for (DeviceConfig dc : getAllDevices()) {
            fireEvent(new ConfigChangeEvent(ConfigChangeEvent.ChangeType.DEVICE_ADDED, dc.getDeviceId(), dc));
        }
        for (ForwardRule rule : getAllRules()) {
            fireEvent(new ConfigChangeEvent(ConfigChangeEvent.ChangeType.RULE_ADDED, rule.getName(), rule));
        }
    }

    private void persistIfEnabled() {
        if (persistenceService != null) {
            persistenceService.saveAll(getAllDevices(), getAllRules());
        }
    }

    public void registerListener(ConfigChangeListener listener) {
        listeners.add(listener);
    }

    public void addDevice(DeviceConfig config) {
        String id = config.getDeviceId();
        if (devices.containsKey(id)) {
            throw new IllegalStateException("Device already exists: " + id);
        }
        devices.put(id, config);
        fireEvent(new ConfigChangeEvent(ConfigChangeEvent.ChangeType.DEVICE_ADDED, id, config));
        persistIfEnabled();
        logger.info("Device added: {}", id);
    }

    public void removeDevice(String deviceId) {
        DeviceConfig removed = devices.remove(deviceId);
        if (removed == null) {
            throw new IllegalArgumentException("Device not found: " + deviceId);
        }
        fireEvent(new ConfigChangeEvent(ConfigChangeEvent.ChangeType.DEVICE_REMOVED, deviceId, removed));
        persistIfEnabled();
        logger.info("Device removed: {}", deviceId);
    }

    public void updateDevice(String deviceId, DeviceConfig config) {
        if (!devices.containsKey(deviceId)) {
            throw new IllegalArgumentException("Device not found: " + deviceId);
        }
        devices.put(deviceId, config);
        fireEvent(new ConfigChangeEvent(ConfigChangeEvent.ChangeType.DEVICE_UPDATED, deviceId, config));
        persistIfEnabled();
        logger.info("Device updated: {}", deviceId);
    }

    public DeviceConfig getDevice(String deviceId) {
        return devices.get(deviceId);
    }

    public List<DeviceConfig> getAllDevices() {
        return List.copyOf(devices.values());
    }

    public int getDeviceCount() {
        return devices.size();
    }

    // --- 转发规则管理 ---

    public void addRule(ForwardRule rule) {
        String name = rule.getName();
        if (rules.containsKey(name)) {
            throw new IllegalStateException("Rule already exists: " + name);
        }
        rules.put(name, rule);
        fireEvent(new ConfigChangeEvent(ConfigChangeEvent.ChangeType.RULE_ADDED, name, rule));
        persistIfEnabled();
        logger.info("Rule added: {}", name);
    }

    public void removeRule(String name) {
        ForwardRule removed = rules.remove(name);
        if (removed == null) {
            throw new IllegalArgumentException("Rule not found: " + name);
        }
        fireEvent(new ConfigChangeEvent(ConfigChangeEvent.ChangeType.RULE_REMOVED, name, removed));
        persistIfEnabled();
        logger.info("Rule removed: {}", name);
    }

    public void updateRule(String name, ForwardRule rule) {
        if (!rules.containsKey(name)) {
            throw new IllegalArgumentException("Rule not found: " + name);
        }
        rules.put(name, rule);
        fireEvent(new ConfigChangeEvent(ConfigChangeEvent.ChangeType.RULE_UPDATED, name, rule));
        persistIfEnabled();
        logger.info("Rule updated: {}", name);
    }

    public void toggleRule(String name, boolean enabled) {
        ForwardRule rule = rules.get(name);
        if (rule == null) {
            throw new IllegalArgumentException("Rule not found: " + name);
        }
        if (rule.getTargets() != null) {
            for (ForwardTarget t : rule.getTargets()) {
                t.setEnabled(enabled);
            }
        }
        fireEvent(new ConfigChangeEvent(ConfigChangeEvent.ChangeType.RULE_TOGGLED, name, rule));
        persistIfEnabled();
        logger.info("Rule toggled: {} enabled={}", name, enabled);
    }

    public ForwardRule getRule(String name) {
        return rules.get(name);
    }

    public List<ForwardRule> getAllRules() {
        return List.copyOf(rules.values());
    }

    public int getRuleCount() {
        return rules.size();
    }

    public DeviceState getDeviceState(String deviceId) {
        if (connectionManager == null) return null;
        return connectionManager.getState(deviceId);
    }

    private void fireEvent(ConfigChangeEvent event) {
        for (ConfigChangeListener listener : listeners) {
            try {
                listener.onConfigChange(event);
            } catch (Exception e) {
                logger.error("ConfigChangeListener error: {}", e.getMessage(), e);
            }
        }
    }
}
