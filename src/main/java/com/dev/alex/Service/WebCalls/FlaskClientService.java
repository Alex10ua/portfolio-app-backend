package com.dev.alex.Service.WebCalls;

import com.dev.alex.Model.Tickers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Service
public class FlaskClientService {

    private final WebClient webClient;

    @Autowired
    public FlaskClientService(WebClient webClient) {
        this.webClient = webClient;
    }

    public ResponseEntity<String> sendSyncPostRequest(String ticker) {
        return webClient.post()
                .uri("/update/auto")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new Tickers(ticker))
                .retrieve()
                .toEntity(String.class)
                .block();
    }

    /** Tells Flask to fetch price history for the ticker and store it in MongoDB. */
    public void refreshPriceHistory(String ticker) {
        try {
            webClient.post()
                    .uri("/history/refresh/{ticker}", ticker)
                    .retrieve()
                    .toBodilessEntity()
                    .block();
        } catch (Exception e) {
            // non-fatal — the caller will read whatever is already in MongoDB
        }
    }
}
