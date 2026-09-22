# springboot4-oauth2 — OAuth2 与 JWT 接口认证（Spring Boot 4.1.1）

系列第 19 篇配套工程。订单服务作为 **OAuth2 资源服务器**：物流 / 报表等内部系统拿
JWT（Bearer 令牌，client_credentials 机器对机器语义）访问接口，不同 scope 对应不同权限。
本应用同时自签发 JWT（演示令牌端点 + JwtEncoder），自签环回演示 oauth2-client 的
RestClient 调用链路。

## 依赖（新坐标，旧 starter 已弃用）

```text
spring-boot-starter-webmvc
spring-boot-starter-security-oauth2-resource-server   # 4.1.1 起替代 starter-oauth2-resource-server
spring-boot-starter-security-oauth2-client
测试：spring-boot-starter-security-test + spring-boot-starter-webmvc-test
```

- 资源服务器自动配置包：`org.springframework.boot.security.oauth2.server.resource.autoconfigure`
- nimbus-jose-jwt 10.9.1（BOM 管理）
- JWT 校验器默认 60 秒时钟偏移（`JwtTimestampValidator.DEFAULT_MAX_CLOCK_SKEW`，字节码实证）

## 密钥生成

RSA 密钥对由 `SigningKeys` 生成（nimbus RSAKeyGenerator，2048 位）：

```bash
mvn -q org.codehaus.mojo:exec-maven-plugin:3.1.0:java \
    -Dexec.mainClass=com.xncoding.oauth2.auth.SigningKeys
```

在工程目录下执行，落两个文件到 `src/main/resources/jwk/`：

- `rs256.json` — RSA JWK（含私钥参数），运行期 `SigningKeyProvider` 加载给 JwtEncoder
- `public.pem` — X.509 公钥 PEM，资源服务器经
  `spring.security.oauth2.resourceserver.jwt.public-key-location` 校验用

## 运行

```bash
mvn spring-boot:run     # 端口 18190
# 或带自签环回演示（启动时 logistics 客户端自动调一次受保护接口，日志落 M2M_CALL 行）
mvn spring-boot:run -Dspring-boot.run.profiles=m2m-demo
```

演示令牌端点与受保护接口：

```bash
# client_credentials 换令牌（reports：orders:read + orders:write）
curl -u reports:reports-secret-2026 -d "grant_type=client_credentials" \
     http://localhost:18190/oauth2/token

# 带 Bearer 令牌访问
curl -H "Authorization: Bearer <access_token>" http://localhost:18190/api/orders
```

| 接口 | 权限 | 无令牌 | read-only 令牌 | read+write 令牌 |
|---|---|---|---|---|
| GET /api/orders | SCOPE_orders:read | 401 | 200 | 200 |
| POST /api/orders | SCOPE_orders:write | 401 | 403 | 201 |
| POST /api/orders/{no}/cancel | SCOPE_orders:write | 401 | 403 | 200 |
| POST /oauth2/token | client_id/secret 校验 | 401（错密钥） | — | — |

演示客户端：`logistics/logistics-secret-2026`（read）、`reports/reports-secret-2026`（read+write）。
令牌端点支持 `ttl_seconds` 参数（默认 600），便于实测过期行为。

## 测试

```bash
mvn test
```

12 个用例全绿（4 类）：

```text
TokenServiceTest          2  编码侧：claim 结构 / kid 头 / 编码器解码器互解
JwtApiSecurityTest        4  jwt() 后处理器验 scope 映射与 401/403 分工
RealSignedJwtTest         5  真实签名链路：有效 200 / 过期 401 / 篡改 401 / aud 不符 401
ClientCredentialsLoopTest 1  client_credentials 全链路：换令牌 -> Bearer 调用 -> 缓存复用
```

## 实测结论（同一次运行）

- reports 换令牌 200，签发耗时 12.6 ms，令牌长 675 字符，expires_in 600
- 有效 JWT 查列表 200，往返 25.4 ms；建单 201，`createdBy` 取自 sub claim
- logistics（只有 orders:read）建单 403，problem+json `urn:problem-type:forbidden`
- 无令牌 401，`WWW-Authenticate: Bearer realm="order-service"`
- 过期令牌：过期 1.5 秒后仍 200（默认 60 秒时钟偏移），越过窗口后 401，
  `WWW-Authenticate: Bearer ... error="invalid_token"`
- 篡改签名段任一有效字符后 401
- claims 解码实录：`sub=reports / aud=order-service / scope=[orders:read, orders:write]`
- 自签环回：`M2M_CALL client=logistics status=200 elapsedMs=248`，应用日志 ERROR 0 行

## 避坑速查

1. 旧 starter `spring-boot-starter-oauth2-resource-server` / `-client` 已弃用，用
   `spring-boot-starter-security-oauth2-*`
2. 自定义 token-uri 的 registration 必须先声明 `client.provider.<id>`，否则启动报
   `Provider ID must be specified`
3. `NimbusJwtEncoder.withKeyPair` 的内部 JWK 默认无 kid，令牌头带 kid 时会报
   `Failed to select a JWK signing key`，用 `jwkPostProcessor` 补
4. 过期令牌默认还能用 60 秒（时钟偏移容忍），排障别当成 bug
5. `public-key-location` 属性名不是 `public-key`，类型是 Resource，classpath: PEM 直接可用
