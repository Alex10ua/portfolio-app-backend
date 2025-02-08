package com.dev.alex.Service.WebCalls;

import com.dev.alex.Model.Tickers;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
public class FlaskClientService {

    private final WebClient webClient;

    @Autowired
    public FlaskClientService(WebClient webClient) {
        this.webClient = webClient;
    }

    public ResponseEntity<String> sendSyncPostRequest(String ticker) {
       return webClient.post()
                .uri("/update_one")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new Tickers(ticker))
                .retrieve()
                .toEntity(String.class)
                .block();
    }
}
