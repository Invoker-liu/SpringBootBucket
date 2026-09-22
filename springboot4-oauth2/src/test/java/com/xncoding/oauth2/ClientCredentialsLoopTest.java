package com.xncoding.oauth2;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * client_credentials 全链路：OAuth2AuthorizedClientManager 先向 /oauth2/token
 * 换真令牌，RestClient 拦截器挂上 Bearer 调受保护接口，两次调用复用缓存令牌。
 * 令牌端点用的是真实运行的随机端口，签发与校验全部走真实代码路径。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ClientCredentialsLoopTest {

    @LocalServerPort
    int port;

    @Test
    void client_credentials_loop_reaches_protected_api_and_caches_token() {
        ClientRegistration registration = ClientRegistration.withRegistrationId("logistics")
                .clientId("logistics")
                .clientSecret("logistics-secret-2026")
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .tokenUri("http://localhost:" + port + "/oauth2/token")
                .scope("orders:read")
                .build();
        ClientRegistrationRepository registrations = new InMemoryClientRegistrationRepository(registration);

        AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(
                        registrations, new InMemoryOAuth2AuthorizedClientService(registrations));
        OAuth2AuthorizedClientProvider provider = OAuth2AuthorizedClientProviderBuilder.builder()
                .clientCredentials()
                .build();
        manager.setAuthorizedClientProvider(provider);

        List<String> seenTokens = new ArrayList<>();
        RestClient rest = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .requestInterceptor(new RecordingBearerInterceptor(manager, "logistics", seenTokens))
                .build();

        List<?> first = rest.get().uri("/api/orders").retrieve().body(List.class);
        List<?> second = rest.get().uri("/api/orders").retrieve().body(List.class);

        assertThat(first).isNotNull();
        assertThat(seenTokens).hasSize(2);
        // 令牌在缓存有效期内复用：两次请求拿到的是同一个 access_token
        assertThat(seenTokens.get(0)).isEqualTo(seenTokens.get(1));
    }

    /** 与 MachineClientConfig.BearerInterceptor 相同的挂载方式，外加记录用过的令牌 */
    record RecordingBearerInterceptor(OAuth2AuthorizedClientManager manager, String registrationId,
                                      List<String> seenTokens)
            implements ClientHttpRequestInterceptor {

        @Override
        public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                            ClientHttpRequestExecution execution) throws IOException {
            OAuth2AuthorizedClient client = manager.authorize(OAuth2AuthorizeRequest
                    .withClientRegistrationId(registrationId)
                    .principal("logistics-cron")
                    .build());
            if (client == null) {
                throw new IllegalStateException("未取得 client credentials 令牌: " + registrationId);
            }
            seenTokens.add(client.getAccessToken().getTokenValue());
            request.getHeaders().setBearerAuth(client.getAccessToken().getTokenValue());
            return execution.execute(request, body);
        }
    }
}
