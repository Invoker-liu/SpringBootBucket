package com.xncoding.security.config;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;

/**
 * 安全过滤器层的 401 / 403 响应体：RFC 9457 风格的 problem+json。
 *
 * 安全过滤器跑在 DispatcherServlet 之前，spring.mvc.problemdetails 管不到它们，
 * 所以 401（未登录）与 403（已登录无权）的响应体要自己写。
 */
@Component
public class SecurityProblemWriter {

    /** null 字段（比如没设置 properties）不输出，与 MVC 渲染 problem+json 的口径一致 */
    private final ObjectMapper mapper = JsonMapper.builder()
            .changeDefaultPropertyInclusion(
                    v -> JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
            .build();

    public void unauthorized(HttpServletRequest request, HttpServletResponse response,
                             org.springframework.security.core.AuthenticationException ex) throws IOException {
        ProblemDetail pd = problem(HttpStatus.UNAUTHORIZED, "未登录", request,
                "请求缺少有效的 Basic 凭据或凭据无效");
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        // Basic 认证的 401 必须带 WWW-Authenticate，客户端靠它知道该用哪种方式认证
        response.setHeader("WWW-Authenticate", "Basic realm=\"ops-console\"");
        write(response, pd);
    }

    public void forbidden(HttpServletRequest request, HttpServletResponse response,
                          org.springframework.security.access.AccessDeniedException ex) throws IOException {
        ProblemDetail pd = problem(HttpStatus.FORBIDDEN, "无权访问", request,
                "当前账号的角色不足以访问该资源");
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
