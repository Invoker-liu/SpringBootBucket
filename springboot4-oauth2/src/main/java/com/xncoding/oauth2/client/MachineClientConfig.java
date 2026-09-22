package com.xncoding.oauth2.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.client.RestClient;

import java.io.IOException;

/**
 * 机器对机器的客户端侧：RestClient 挂 OAuth2AuthorizedClientManager，
 * 每次请求前向管理器要令牌，没有或已过期自动走 client_credentials 取新令牌。
 * client 注册信息来自 application.yml 的 spring.security.oauth2.client.registration。
 */
@Configuration
public class MachineClientConfig {

    @Bean
    OAuth2AuthorizedClientManager authorizedClientManager(ClientRegistrationRepository registrations) {
        OAuth2AuthorizedClientProvider provider = OAuth2AuthorizedClientProviderBuilder.builder()
                .clientCredentials()
                .build();
        AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(
                        registrations, new InMemoryOAuth2AuthorizedClientService(registrations));
        manager.setAuthorizedClientProvider(provider);
        return manager;
    }

    @Bean
    RestClient logisticsRestClient(OAuth2AuthorizedClientManager manager,
                                   @Value("${app.base-url:http://localhost:18190}") String baseUrl) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestInterceptor(new BearerInterceptor(manager, "logistics", "logistics-cron"))
                .build();
    }

    /**
     * 每次请求前找 AuthorizedClientManager 拿令牌：缓存命中直接用，
     * 缓存没有或临近过期，管理器按 client_credentials 自动换取新令牌。
     */
    record BearerInterceptor(OAuth2AuthorizedClientManager manager, String registrationId, String principal)
            implements ClientHttpRequestInterceptor {

        @Override
        public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                            ClientHttpRequestExecution execution) throws IOException {
            OAuth2AuthorizedClient client = manager.authorize(OAuth2AuthorizeRequest
                    .withClientRegistrationId(registrationId)
                    .principal(principal)
                    .build());
            if (client == null) {
                throw new IllegalStateException("未取得 client credentials 令牌: " + registrationId);
            }
            request.getHeaders().setBearerAuth(client.getAccessToken().getTokenValue());
            return execution.execute(request, body);
        }
    }
}
