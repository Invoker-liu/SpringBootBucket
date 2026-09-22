# springboot4-security

Spring Boot 4 系列第 18 篇配套工程：Spring Security 7 权限管理。

运营管理后台场景：订单接口按角色分级。查询（OPERATOR / ADMIN 都可看），取消订单与审计记录只给 ADMIN（路径级 + 方法级 `@PreAuthorize` 双层收敛）。认证走 httpBasic，用户存 MySQL `sec_users` 表（`{bcrypt}` 密文），401 / 403 响应体为 RFC 9457 风格的 problem+json。

## 版本

| 项 | 版本 |
|---|---|
| Spring Boot | 4.1.1 |
| Spring Security | 7.1.1（BOM 管理） |
| JDK | 21 |

## 运行

依赖树莓派上的 MySQL（192.168.1.97:3306，root / root123456）。首次先建库建表：

```bash
bash ../.workbuddy/reset-security-db.sh     # 建库 springboot4_security + 建表
```

启动（应用会自动建表并种入两个演示账号）：

```bash
mvn spring-boot:run                         # 默认端口 18180
```

## 测试账号

| 用户名 | 密码 | 角色 | 能做什么 |
|---|---|---|---|
| admin | admin123 | ADMIN | 全部接口：查询、建单、取消订单、读审计 |
| operator | operator123 | OPERATOR | 查询与建单；取消订单 403、读审计 403 |

演示账号由 `UserSeedRunner` 在启动时种入（BCrypt 编码后落库，已存在则不覆盖）。本地不连库的原型演示可以加 `--spring.profiles.active=memory`，用户改由 `InMemoryUserDetailsManager` 提供。

## 接口一览

| 方法 | 路径 | 最低权限 | 说明 |
|---|---|---|---|
| GET | /api/orders | OPERATOR | 订单列表 |
| POST | /api/orders | OPERATOR | 建单，返回 201 + Location |
| POST | /api/orders/{orderNo}/cancel | ADMIN（服务层 `@PreAuthorize`） | 取消订单并写审计 |
| GET | /api/audit | ADMIN | 审计记录（最近 limit 条） |

## 快速体验

```bash
# 未登录 -> 401 problem+json
curl -i http://localhost:18180/api/orders

# OPERATOR 打 ADMIN 接口 -> 403 problem+json
curl -i -u operator:operator123 http://localhost:18180/api/audit

# ADMIN 取消订单 -> 200
curl -i -u admin:admin123 -X POST http://localhost:18180/api/orders/SK-SEC-1/cancel

# ADMIN 读审计 -> 200
curl -i -u admin:admin123 http://localhost:18180/api/audit
```

## 测试

18 个用例，全部连真实 MySQL：

```bash
mvn test
```

- `PasswordEncodingTest`（4）：BCrypt 往返、DelegatingPasswordEncoder 前缀分发、`sec_users` 真实密文 matches
- `OrderApiSecurityTest`（7）：匿名 401、OPERATOR 200/403、方法级 403、ADMIN 全通并落审计行
- `HttpBasicAuthTest`（5）：真实 Basic 认证链路（JDBC 用户 + bcrypt）、错误密码 401 + 失败事件、用户不存在伪装成凭据错误
- `MethodSecurityTest`（2）：绕过 HTTP 直接调服务层 bean，`@PreAuthorize` 照样拦

## 实测结论（2026-09-20 验证轮）

- 10 个真实 HTTP 场景全部符合预期，应用日志 ERROR 0 行
- 匿名请求 401，响应头带 `WWW-Authenticate: Basic realm="ops-console"`，响应体 problem+json
- OPERATOR 读审计 403、取消订单 403（拦截发生在 `@PreAuthorize`）；ADMIN 对应场景 200
- 错误密码 401，`AUTH_EVENT failure principal=admin reason=BadCredentialsException`；成功认证有 `AUTH_EVENT success` 事件
- 密文形如 `{bcrypt}$2a$10$...`，`matches("admin123", hash)` 为 true、`matches("admin124", hash)` 为 false

## 关键实现

| 文件 | 职责 |
|---|---|
| `config/SecurityConfig.java` | SecurityFilterChain（路径级授权 + httpBasic + STATELESS + 关 CSRF）、PasswordEncoder、JdbcUserDetailsManager |
| `config/SecurityProblemWriter.java` | 过滤器层 401 / 403 的 problem+json 响应体 |
| `config/UserSeedRunner.java` | 启动种入演示账号（BCrypt） |
| `config/AuthAuditListener.java` | 认证成功 / 失败事件日志 |
| `order/OrderService.java` | `@PreAuthorize("hasRole('ADMIN')")` 在 cancel 上 |
| `audit/` | 审计落库与 ADMIN 专属查询接口 |
