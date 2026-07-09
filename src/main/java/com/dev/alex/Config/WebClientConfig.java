package com.dev.alex.Config;

import io.netty.channel.ChannelOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

@EnableAsync
@Configuration
public class WebClientConfig {

    @Value("${app.rest.base-url}")
    private String baseUrl;

    // Flask market-data calls are made synchronously during transaction creation
    // (FlaskClientService.block()), and /update/auto can do a slow, rate-limited Yahoo
    // fetch. Bound it so a hung/slow Flask can't pin a Spring request thread indefinitely.
    @Bean
    public WebClient webClient(WebClient.Builder builder) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)
                .responseTimeout(Duration.ofSeconds(30));
        return builder
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
