package com.opcua.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opcua.forward.config.ForwardRule;
import com.opcua.model.DeviceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ConfigPersistenceService {

    private static final Logger logger = LoggerFactory.getLogger(ConfigPersistenceService.class);

    private final ObjectMapper yamlMapper;
    private final Path configFile;

    public ConfigPersistenceService(ObjectMapper objectMapper, Path configFile) {
        this.yamlMapper = objectMapper.copy();
        this.yamlMapper.findAndRegisterModules();
        this.configFile = configFile;
    }

    public ConfigPersistenceService(Path configFile) {
        this(new ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory()), configFile);
    }

    public void saveAll(List<DeviceConfig> devices, List<ForwardRule> rules) {
        RuntimeConfigData data = new RuntimeConfigData();
        data.setDevices(devices != null ? devices : Collections.emptyList());
        data.setRules(rules != null ? rules : Collections.emptyList());
        atomicWrite(data);
    }

    public void saveDevices(List<DeviceConfig> devices) {
        RuntimeConfigData existing = loadAllData();
        existing.setDevices(devices != null ? devices : Collections.emptyList());
        atomicWrite(existing);
    }

    public void saveRules(List<ForwardRule> rules) {
        RuntimeConfigData existing = loadAllData();
        existing.setRules(rules != null ? rules : Collections.emptyList());
        atomicWrite(existing);
    }

    public List<DeviceConfig> loadDevices() {
        return loadAllData().getDevices();
    }

    public List<ForwardRule> loadRules() {
        return loadAllData().getRules();
    }

    private RuntimeConfigData loadAllData() {
        if (!Files.exists(configFile)) {
            return new RuntimeConfigData();
        }
        try {
            return yamlMapper.readValue(configFile.toFile(), RuntimeConfigData.class);
        } catch (IOException e) {
            logger.warn("Failed to load config from {}, using empty config", configFile, e);
            return new RuntimeConfigData();
        }
    }

    private void atomicWrite(RuntimeConfigData data) {
        try {
            if (configFile.getParent() != null) {
                Files.createDirectories(configFile.getParent());
            }
            Path tempFile = configFile.resolveSibling(configFile.getFileName() + ".tmp");
            yamlMapper.writeValue(tempFile.toFile(), data);
            Files.move(tempFile, configFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            logger.debug("Config persisted to {}", configFile);
        } catch (IOException e) {
            logger.error("Failed to persist config to {}", configFile, e);
            throw new RuntimeException("Failed to persist config", e);
        }
    }

    /**
     * YAML 文件中存储的运行时配置数据结构。
     */
    public static class RuntimeConfigData {
        private List<DeviceConfig> devices = new ArrayList<>();
        private List<ForwardRule> rules = new ArrayList<>();

        public List<DeviceConfig> getDevices() { return devices; }
        public void setDevices(List<DeviceConfig> devices) { this.devices = devices; }
        public List<ForwardRule> getRules() { return rules; }
        public void setRules(List<ForwardRule> rules) { this.rules = rules; }
    }
}
