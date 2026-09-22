package com.xncoding.oauth2.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;

/**
 * 资源服务器的 401 / 403 响应体：RFC 9457 风格的 problem+json。
 *
 * Bearer 令牌的校验发生在过滤器层，spring.mvc.problemdetails 管不到这里，
 * 响应体要自己写。401 必须带 WWW-Authenticate: Bearer，调用方靠它知道
 * 该补什么样的凭据，error 值按 RFC 6750 区分令牌无效与凭据缺失。
 */
@Component
public class BearerProblemWriter {

    /** null 字段（比如没设置 properties）不输出，与 MVC 渲染 problem+json 的口径一致 */
    private final ObjectMapper mapper = JsonMapper.builder()
            .changeDefaultPropertyInclusion(
                    v -> JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
            .build();

    public void unauthorized(HttpServletRequest request, HttpServletResponse response,
                             AuthenticationException ex) throws IOException {
        ProblemDetail pd = problem(HttpStatus.UNAUTHORIZED, "未认证", request,
                "请求缺少有效的 Bearer 令牌或令牌已失效");
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        // RFC 6750：error=invalid_token 表示带了令牌但校验失败，没带令牌只给 realm
        String error = ex instanceof InvalidBearerTokenException ? ", error=\"invalid_token\"" : "";
        response.setHeader("WWW-Authenticate", "Bearer realm=\"order-service\"" + error);
        write(response, pd);
    }

    public void forbidden(HttpServletRequest request, HttpServletResponse response,
                          AccessDeniedException ex) throws IOException {
        ProblemDetail pd = problem(HttpStatus.FORBIDDEN, "无权访问", request,
                "当前令牌的 scope 不足以访问该资源");
        response.setStatus(HttpStatus.FORBIDDEN.value());
        write(response, pd);
    }

    private ProblemDetail problem(HttpStatus status, String title, HttpServletRequest request, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create("urn:problem-type:" + switch (status) {
            case UNAUTHORIZED -> "unauthorized";
            case FORBIDDEN -> "forbidden";
            default -> "security";
        }));
        pd.setTitle(title);
        pd.setInstance(URI.create(request.getRequestURI()));
        return pd;
    }

    private void write(HttpServletResponse response, ProblemDetail pd) throws IOException {
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getWriter(), pd);
    }
}
