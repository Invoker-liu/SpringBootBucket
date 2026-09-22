package com.xncoding.oauth2;

import com.xncoding.oauth2.auth.SigningKeyProvider;
import com.xncoding.oauth2.auth.TokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 自签发 JWT 的编码侧：claim 结构、kid 头，以及编码器与解码器
 * 用同一对密钥能互相解开（JWK 与 PEM 的一致性）。
 */
@SpringBootTest
class TokenServiceTest {

    @Autowired
    TokenService tokens;

    @Autowired
    SigningKeyProvider keys;

    @Test
    void issued_token_carries_expected_claims_and_header() {
        Jwt jwt = tokens.issue("reports-cron", List.of("orders:read", "orders:write"),
                TokenService.AUDIENCE, Duration.ofSeconds(600));

        assertThat(jwt.getSubject()).isEqualTo("reports-cron");
        assertThat(jwt.getAudience()).containsExactly("order-service");
        assertThat(jwt.getClaimAsStringList("scope"))
                .containsExactly("orders:read", "orders:write");
        assertThat(jwt.getExpiresAt()).isAfter(jwt.getIssuedAt());
        assertThat(jwt.getHeaders().get("kid")).isEqualTo(keys.keyId());
        assertThat(jwt.getHeaders().get("typ")).isEqualTo("JWT");
        assertThat(String.valueOf(jwt.getHeaders().get("alg"))).isEqualTo("RS256");
    }

    @Test
    void decoder_built_from_public_key_reads_encoder_output() {
        Jwt issued = tokens.issue("logistics-cron", List.of("orders:read"),
                TokenService.AUDIENCE, Duration.ofSeconds(600));

        JwtDecoder decoder = NimbusJwtDecoder.withPublicKey(keys.publicKey()).build();
        Jwt decoded = decoder.decode(issued.getTokenValue());

        assertThat(decoded.getSubject()).isEqualTo("logistics-cron");
        assertThat(decoded.getClaimAsStringList("scope")).containsExactly("orders:read");
    }
}
