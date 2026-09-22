package com.xncoding.openapi.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityScheme;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 全局信息 + 安全方案 + 可复用的响应组件。
 * 401/403/404/409 这些响应体各声明一次，控制器里用 @ApiResponse(ref=...) 引用，
 * 改文案只动这一处。
 */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI orderOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("订单服务 API")
                        .description("SpringBoot4 系列示例：前端与第三方联调的订单接口文档")
                        .version("v1.0.0")
                        .contact(new Contact().name("订单服务团队")))
                .components(new Components()
                        // swagger-ui 调试支持：右上角 Authorize 弹出用户名密码框，
                        // 凭据以 Basic 头随请求发出，直接调 /api/admin 接口
                        .addSecuritySchemes("basicAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("basic")
                                .description("HTTP Basic，管理侧账号见 application.yml 的 spring.security.user.*"))
                        .addResponses("BadRequest", shared(400, "请求参数校验失败",
                                "urn:problem-type:bad-request", "请求参数校验失败"))
                        .addResponses("Unauthorized", shared(401, "未认证",
                                "urn:problem-type:unauthorized", "未提供或提供了无效的认证信息"))
                        .addResponses("Forbidden", shared(403, "权限不足",
                                "urn:problem-type:forbidden", "认证通过但无权访问该资源"))
                        .addResponses("NotFound", shared(404, "资源不存在",
                                "urn:problem-type:order-not-found", "订单不存在"))
                        .addResponses("Duplicate", shared(409, "单号重复",
                                "urn:problem-type:duplicate-order", "订单号已存在")));
    }

    private ApiResponse shared(int status, String title, String type, String detail) {
        Schema<?> problem = new Schema<>()
                .type("object").description("RFC 9457 problem+json")
                .addProperties("type", new Schema<>().type("string").example(type))
                .addProperties("title", new Schema<>().type("string").example(title))
                .addProperties("status", new Schema<>().type("integer").example(status))
                .addProperties("detail", new Schema<>().type("string").example(detail));
        return new ApiResponse().description(title)
                .content(new Content().addMediaType("application/problem+json",
                        new MediaType().schema(problem)));
    }
}
