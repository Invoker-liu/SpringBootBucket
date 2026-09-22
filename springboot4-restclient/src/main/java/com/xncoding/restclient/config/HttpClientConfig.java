package com.xncoding.restclient.config;

import com.xncoding.restclient.api.LogisticsApi;
import com.xncoding.restclient.api.RiskApi;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.time.Duration;

/**
 * 两个上游客户端的装配：RestClient.Builder 注入后各自加工出独立实例
 * （不同 baseUrl、不同超时），再经 HttpServiceProxyFactory 生成声明式接口代理。
 */
@Configuration
public class HttpClientConfig {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

    @Bean
    RestClient logisticsRestClient(RestClient.Builder builder, UpstreamProperties props) {
        UpstreamProperties.Client cfg = props.logistics();
        return builder
                .requestFactory(factory(cfg.connectTimeout(), cfg.readTimeout()))
                .baseUrl(cfg.baseUrl())
                .defaultHeader("X-Source", "order-service")
                .build();
    }

    @Bean
    RestClient riskRestClient(RestClient.Builder builder, UpstreamProperties props) {
        UpstreamProperties.Client cfg = props.risk();
        return builder
                .requestFactory(factory(cfg.connectTimeout(), cfg.readTimeout()))
                .baseUrl(cfg.baseUrl())
                .defaultHeader("X-Source", "order-service")
                .build();
    }

    @Bean
    LogisticsApi logisticsApi(RestClient logisticsRestClient) {
        return proxyOf(logisticsRestClient, LogisticsApi.class);
    }

    @Bean
    RiskApi riskApi(RestClient riskRestClient) {
        return proxyOf(riskRestClient, RiskApi.class);
    }

    private static org.springframework.http.client.ClientHttpRequestFactory factory(
            Duration connectTimeout, Duration readTimeout) {
        HttpClientSettings settings = HttpClientSettings.defaults()
                .withConnectTimeout(connectTimeout)
                .withReadTimeout(readTimeout);
        return ClientHttpRequestFactoryBuilder.jdk().build(settings);
    }

    private static <T> T proxyOf(RestClient restClient, Class<T> apiType) {
        return HttpServiceProxyFactory.builder()
                .exchangeAdapter(RestClientAdapter.create(restClient))
                .build()
                .createClient(apiType);
    }
}
