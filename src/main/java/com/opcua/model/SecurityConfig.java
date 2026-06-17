package com.opcua.model;

/**
 * OPC UA 安全认证配置。
 */
public class SecurityConfig {

    /** 安全策略，默认 None */
    private String policy = "None";

    /** 安全模式，默认 None */
    private String mode = "None";

    /** 客户端证书路径 */
    private String certificate;

    /** 证书密码 */
    private String certificatePassword;

    /** 用户名认证 */
    private String username;

    /** 密码认证 */
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
        return (certificate != null && !certificate.isBlank())
                || (username != null && !username.isBlank());
    }

    // --- Getters / Setters ---

    public String getPolicy() {
        return policy;
    }

    public void setPolicy(String policy) {
        this.policy = policy;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getCertificate() {
        return certificate;
    }

    public void setCertificate(String certificate) {
        this.certificate = certificate;
    }

    public String getCertificatePassword() {
        return certificatePassword;
    }

    public void setCertificatePassword(String certificatePassword) {
        this.certificatePassword = certificatePassword;
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
                ", mode='" + mode + '\'' +
                ", certificate='" + certificate + '\'' +
                ", username='" + username + '\'' +
                '}';
    }
}
