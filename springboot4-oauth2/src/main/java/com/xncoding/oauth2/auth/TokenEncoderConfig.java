package com.xncoding.oauth2.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * 自签发 JWT 的编码器。
 *
 * 校验侧（JwtDecoder）不在这里声明：application.yml 配好
 * spring.security.oauth2.resourceserver.jwt.public-key-location 与 audiences 后，
 * Boot 的 JwtDecoderConfiguration 自动装配 PublicKey 路线的解码器并挂上 aud 校验。
 */
@Configuration
public class TokenEncoderConfig {

    @Bean
    JwtEncoder jwtEncoder(SigningKeyProvider keys) {
        return NimbusJwtEncoder.withKeyPair(keys.publicKey(), keys.privateKey())
                .algorithm(SignatureAlgorithm.RS256)
                // withKeyPair 构造的内部 JWK 默认没有 kid，而签出的令牌头里带 kid，
                // 编码器按键选择 JWK，两边不一致会报 Failed to select a JWK signing key
                .jwkPostProcessor(jwk -> jwk.keyID(keys.keyId()))
                .build();
    }
}
