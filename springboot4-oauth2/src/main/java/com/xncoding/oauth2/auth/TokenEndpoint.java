package com.xncoding.oauth2.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * 演示令牌端点：POST /oauth2/token，client_credentials 语义。
 *
 * 生产环境的这个端点属于独立授权服务器；本工程自签环回，签发与校验共用
 * 本地密钥，令牌端点校验 client_id/client_secret（支持 HTTP Basic 或表单字段），
 * 请求的 scope 必须是客户端注册 scope 的子集。
 */
@RestController
@RequestMapping("/oauth2/token")
public class TokenEndpoint {

    private static final Logger log = LoggerFactory.getLogger(TokenEndpoint.class);

    /** 演示客户端表：真实系统里来自授权服务器的数据库 */
    private static final Map<String, ClientEntry> CLIENTS = Map.of(
            "logistics", new ClientEntry("logistics-secret-2026", List.of("orders:read")),
            "reports", new ClientEntry("reports-secret-2026", List.of("orders:read", "orders:write")));

    private record ClientEntry(String secret, List<String> scopes) {
    }

    private final TokenService tokens;

    public TokenEndpoint(TokenService tokens) {
        this.tokens = tokens;
    }

    @PostMapping(consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<?> token(@RequestParam Map<String, String> form,
                                   HttpServletRequest request) {
        if (!"client_credentials".equals(form.get("grant_type"))) {
            return problem(HttpStatus.BAD_REQUEST, "unsupported_grant_type",
                    "grant_type 只支持 client_credentials");
        }
        String[] basic = basicCredentials(request);
        String clientId = basic != null ? basic[0] : form.get("client_id");
        String clientSecret = basic != null ? basic[1] : form.get("client_secret");
        ClientEntry entry = clientId == null ? null : CLIENTS.get(clientId);
        // MessageDigest.isEqual 做常量时间比较，避免逐字符比较泄露时序信息
        if (entry == null || clientSecret == null
                || !MessageDigest.isEqual(entry.secret().getBytes(StandardCharsets.UTF_8),
                        clientSecret.getBytes(StandardCharsets.UTF_8))) {
            log.info("JWT_EVENT token_denied clientId={}", clientId);
            return problem(HttpStatus.UNAUTHORIZED, "invalid_client", "client_id 或 client_secret 不正确");
        }
        List<String> requested = form.containsKey("scope")
                ? List.of(form.get("scope").trim().split("\\s+"))
                : entry.scopes();
        if (!entry.scopes().containsAll(requested)) {
            return problem(HttpStatus.BAD_REQUEST, "invalid_scope",
                    "请求的 scope 超出该客户端的注册范围");
        }
        long ttl = 600;
        if (form.containsKey("ttl_seconds")) {
            try {
                ttl = Long.parseLong(form.get("ttl_seconds"));
            } catch (NumberFormatException e) {
                return problem(HttpStatus.BAD_REQUEST, "invalid_request", "ttl_seconds 不是数字");
            }
        }
        Jwt jwt = tokens.issue(clientId, requested, TokenService.AUDIENCE, Duration.ofSeconds(ttl));
        return ResponseEntity.ok(Map.of(
                "access_token", jwt.getTokenValue(),
                "token_type", "Bearer",
                "expires_in", ttl,
                "scope", String.join(" ", requested)));
    }

    private String[] basicCredentials(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Basic ")) {
            return null;
        }
        String decoded = new String(Base64.getDecoder().decode(auth.substring(6)), StandardCharsets.UTF_8);
        int split = decoded.indexOf(':');
        return split < 0 ? null : new String[]{decoded.substring(0, split), decoded.substring(split + 1)};
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create("urn:problem-type:token-" + code));
        pd.setTitle(code);
        return ResponseEntity.status(status).body(pd);
    }
}
