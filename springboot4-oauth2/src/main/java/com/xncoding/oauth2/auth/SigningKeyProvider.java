package com.xncoding.oauth2.auth;

import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

/**
 * RSA 签名密钥的运行期持有者：从 classpath 的 jwk/rs256.json（RSA JWK，含私钥参数）加载。
 *
 * 重新生成密钥对时跑 SigningKeys 的 main，同目录落两个文件：
 * rs256.json 供本类与编码器用，public.pem（X.509 SubjectPublicKeyInfo 的 PEM）
 * 供资源服务器 public-key-location 校验用。
 */
@Component
public class SigningKeyProvider {

    private final RSAKey jwk;

    public SigningKeyProvider() {
        try {
            String json = new org.springframework.core.io.ClassPathResource("jwk/rs256.json")
                    .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            this.jwk = RSAKey.parse(json);
        } catch (IOException e) {
            throw new UncheckedIOException("读取 jwk/rs256.json 失败，先跑 SigningKeys 的 main 生成密钥", e);
        } catch (java.text.ParseException e) {
            throw new IllegalStateException("jwk/rs256.json 不是合法的 RSA JWK", e);
        }
    }

    public String keyId() {
        return jwk.getKeyID();
    }

    public RSAPublicKey publicKey() {
        try {
            return jwk.toRSAPublicKey();
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException("JWK 转 RSAPublicKey 失败", e);
        }
    }

    public RSAPrivateKey privateKey() {
        try {
            return jwk.toRSAPrivateKey();
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException("JWK 转 RSAPrivateKey 失败", e);
        }
    }
}
