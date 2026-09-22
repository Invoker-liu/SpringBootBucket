package com.xncoding.restclient.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "upstream")
public record UpstreamProperties(Client logistics, Client risk) {

    public UpstreamProperties {
        if (logistics == null) {
            logistics = new Client("http://localhost:18141", Duration.ofSeconds(2), Duration.ofSeconds(3));
        }
        if (risk == null) {
            risk = new Client("http://localhost:18141", Duration.ofSeconds(2), Duration.ofMillis(1500));
        }
    }

    public record Client(String baseUrl,
                         @DefaultValue("2s") Duration connectTimeout,
                         @DefaultValue("3s") Duration readTimeout) {
    }
}
