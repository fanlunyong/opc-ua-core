package com.opcua.config;

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
    private final List<ConfigChangeListener> listeners = new CopyOnWriteArrayList<>();

    private com.opcua.core.ConnectionManager connectionManager;

    public void setConnectionManager(com.opcua.core.ConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
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
        logger.info("Device added: {}", id);
    }

    public void removeDevice(String deviceId) {
        DeviceConfig removed = devices.remove(deviceId);
        if (removed == null) {
            throw new IllegalArgumentException("Device not found: " + deviceId);
        }
        fireEvent(new ConfigChangeEvent(ConfigChangeEvent.ChangeType.DEVICE_REMOVED, deviceId, removed));
        logger.info("Device removed: {}", deviceId);
    }

    public void updateDevice(String deviceId, DeviceConfig config) {
        if (!devices.containsKey(deviceId)) {
            throw new IllegalArgumentException("Device not found: " + deviceId);
        }
        devices.put(deviceId, config);
        fireEvent(new ConfigChangeEvent(ConfigChangeEvent.ChangeType.DEVICE_UPDATED, deviceId, config));
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
