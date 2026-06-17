package com.opcua.wrapper;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * X509 证书和私钥加载工具。
 * 支持 DER 和 PEM 格式的证书，以及 PKCS#8 PEM 格式的私钥。
 *
 * <p>用于替代 Milo server-examples 中的 KeyStoreLoader（该工具仅 test scope 可用）。</p>
 */
public final class CertificateLoader {

    private File clientCertificateFile;
    private File clientKeyPairFile;
    private String keyPairPassword = "";

    private CertificateLoader() {
    }

    /**
     * 创建新的 CertificateLoader 实例。
     */
    public static CertificateLoader create() {
        return new CertificateLoader();
    }

    /**
     * 设置客户端证书文件（DER 或 PEM 格式）。
     */
    public CertificateLoader setClientCertificate(File file) {
        this.clientCertificateFile = file;
        return this;
    }

    /**
     * 设置客户端私钥文件（PKCS#8 PEM 格式）。
     */
    public CertificateLoader setClientKeyPairFile(File file) {
        this.clientKeyPairFile = file;
        return this;
    }

    /**
     * 设置私钥密码（无密码保护时为空字符串）。
     */
    public CertificateLoader setKeyPairPassword(String password) {
        this.keyPairPassword = (password != null) ? password : "";
        return this;
    }

    /**
     * 加载并返回 X509 客户端证书。
     */
    public X509Certificate getClientCertificate() throws Exception {
        byte[] certBytes = readFileBytes(clientCertificateFile);
        // 尝试 DER 格式
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(certBytes));
        } catch (Exception e) {
            // 可能为 PEM 格式，尝试解码
            return decodePemCertificate(certBytes);
        }
    }

    /**
     * 加载并返回客户端密钥对。
     */
    public KeyPair getClientKeyPair() throws Exception {
        File keyFile = (clientKeyPairFile != null) ? clientKeyPairFile : clientCertificateFile;
        byte[] keyBytes = readFileBytes(keyFile);
        byte[] decoded = decodePemBytes(keyBytes, "PRIVATE KEY");

        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(decoded);
        return new KeyPair(getClientCertificate().getPublicKey(),
                keyFactory.generatePrivate(keySpec));
    }

    // --- 内部方法 ---

    private static byte[] readFileBytes(File file) throws IOException {
        return Files.readAllBytes(file.toPath());
    }

    private static X509Certificate decodePemCertificate(byte[] pemBytes) throws Exception {
        byte[] decoded = decodePemBytes(pemBytes, "CERTIFICATE");
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(decoded));
    }

    private static byte[] decodePemBytes(byte[] pemBytes, String label) {
        String content = new String(pemBytes, StandardCharsets.UTF_8);
        String beginMarker = "-----BEGIN " + label + "-----";
        String endMarker = "-----END " + label + "-----";

        int beginIdx = content.indexOf(beginMarker);
        int endIdx = content.indexOf(endMarker);

        if (beginIdx >= 0 && endIdx > beginIdx) {
            String base64 = content.substring(beginIdx + beginMarker.length(), endIdx);
            base64 = base64.replaceAll("\\s", "");
            return Base64.getDecoder().decode(base64);
        }

        // 并非 PEM 格式，当作原始二进制
        return pemBytes;
    }
}
