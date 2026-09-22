package com.xncoding.oauth2.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 用本地 RSA 密钥自签发 JWT：演示令牌端点与测试都从这里拿令牌。
 *
 * 真实系统的签发方是独立的授权服务器；本工程为了闭环演示，签发与校验
 * 共用同一对密钥，claim 结构（sub / scope / aud / exp）与生产一致。
 */
@Service
public class TokenService {

    private static final Logger log = LoggerFactory.getLogger(TokenService.class);

    /** 受众：资源服务器按 application.yml 的 audiences 属性校验该 claim */
    public static final String AUDIENCE = "order-service";

    private final JwtEncoder encoder;
    private final SigningKeyProvider keys;

    public TokenService(JwtEncoder encoder, SigningKeyProvider keys) {
        this.encoder = encoder;
        this.keys = keys;
    }

    /**
     * audience 传 null 时签出的令牌不带 aud claim，用于验证资源服务器的受众校验。
     */
    public Jwt issue(String subject, List<String> scopes, String audience, Duration ttl) {
        return issue(subject, scopes, audience, Instant.now(), Instant.now().plus(ttl));
    }

    /** 签发时间与过期时间可指定的重载：测试用它构造已过期的令牌 */
    public Jwt issue(String subject, List<String> scopes, String audience,
                     Instant issuedAt, Instant expiresAt) {
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer("http://order-service.internal")
                .subject(subject)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim("scope", scopes);
        if (audience != null && !audience.isBlank()) {
            claims.audience(List.of(audience));
        }
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256)
                .keyId(keys.keyId())
                .type("JWT")
                .build();
        Jwt jwt = encoder.encode(JwtEncoderParameters.from(header, claims.build()));
        log.info("JWT_EVENT issued subject={} scope={} expiresAt={}",
                subject, String.join(",", scopes), expiresAt);
        return jwt;
    }
}
