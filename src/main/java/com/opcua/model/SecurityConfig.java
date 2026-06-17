package com.opcua.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * OPC UA 安全认证配置。
 */
public class SecurityConfig {

    /** 安全策略，默认 None */
    private String policy = "None";

    /** 客户端证书路径 */
    private String certificatePath;

    /** 客户端私钥路径 */
    @JsonIgnore
    private String privateKeyPath;

    /** 用户名认证 */
    private String username;

    /** 密码认证 */
    @JsonIgnore
    private String password;

    public SecurityConfig() {
    }

    /**
     * 判断是否启用了安全认证。
     * 当证书或用户名/密码任一配置不为空时，视为启用了安全认证。
     *
     * @return true 如果启用了安全认证
     */
    public boolean isSecure() {
        return (certificatePath != null && !certificatePath.isBlank())
                || (username != null && !username.isBlank());
    }

    // --- Getters / Setters ---

    public String getPolicy() {
        return policy;
    }

    public void setPolicy(String policy) {
        this.policy = policy;
    }

    public String getCertificatePath() {
        return certificatePath;
    }

    public void setCertificatePath(String certificatePath) {
        this.certificatePath = certificatePath;
    }

    public String getPrivateKeyPath() {
        return privateKeyPath;
    }

    public void setPrivateKeyPath(String privateKeyPath) {
        this.privateKeyPath = privateKeyPath;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    @Override
    public String toString() {
        return "SecurityConfig{" +
                "policy='" + policy + '\'' +
                ", certificatePath='" + certificatePath + '\'' +
                ", username='" + username + '\'' +
                '}';
    }
}
