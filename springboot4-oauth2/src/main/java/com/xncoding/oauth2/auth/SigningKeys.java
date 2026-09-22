package com.xncoding.oauth2.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * RSA 签名密钥对的生成工具：部署前跑一次 main，把密钥落进 resources/jwk。
 *
 * 生成两个文件：
 * - rs256.json   RSA JWK（含私钥参数），运行期由 SigningKeyProvider 加载
 * - public.pem   X.509 SubjectPublicKeyInfo 的 PEM，资源服务器 public-key-location 校验用
 */
public final class SigningKeys {

    private SigningKeys() {
    }

    public static void main(String[] args) throws Exception {
        Path outDir = Path.of(args.length > 0 ? args[0] : "src/main/resources/jwk");
        RSAKey jwk = new RSAKeyGenerator(2048)
                .keyUse(KeyUse.SIGNATURE)
                .keyID("order-" + Long.toHexString(System.currentTimeMillis()))
                .algorithm(JWSAlgorithm.RS256)
                .generate();

        Files.createDirectories(outDir);
        Files.writeString(outDir.resolve("rs256.json"), jwk.toJSONString());
        Files.writeString(outDir.resolve("public.pem"), toPem(jwk.toRSAPublicKey().getEncoded()));
        System.out.println("RSA 密钥对已生成 kid=" + jwk.getKeyID() + " -> " + outDir.toAbsolutePath());
    }

    private static String toPem(byte[] x509Encoded) {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(x509Encoded);
        return "-----BEGIN PUBLIC KEY-----\n" + base64 + "\n-----END PUBLIC KEY-----\n";
    }
}
