package com.dev.alex.Service.WebCalls;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Service
public class FlaskClientService {

    private final WebClient webClient;

    @Autowired
    public FlaskClientService(WebClient webClient) {
        this.webClient = webClient;
    }

    public ResponseEntity<String> sendSyncPostRequest(String ticker) {
        return sendSyncPostRequest(ticker, null);
    }

    /**
     * assetType (e.g. "CRYPTO") lets Flask route to the right provider even when
     * the holding doesn't exist yet — its holdings-based lookup only works after.
     */
    public ResponseEntity<String> sendSyncPostRequest(String ticker, String assetType) {
        Map<String, String> body = new HashMap<>();
        body.put("ticker", ticker);
        if (assetType != null) {
            body.put("assetType", assetType);
        }
        return webClient.post()
                .uri("/update/auto")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .toEntity(String.class)
                .block(Duration.ofSeconds(35)); // hard ceiling; connect/response timeouts set on the WebClient
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

    /**
     * Tells Flask to (re)fetch SEC EDGAR fundamentals for the ticker into MongoDB.
     * Synchronous — the caller reads the freshly-written doc right after this returns.
     */
    public ResponseEntity<String> refreshFundamentals(String ticker) {
        return postTicker("/update/fundamentals", ticker);
    }

    /**
     * Refresh only marketData.statistics (Yahoo key statistics) for the ticker.
     * One .info request on the Flask side — no price-history download.
     */
    public ResponseEntity<String> refreshStatistics(String ticker) {
        return postTicker("/update/statistics", ticker);
    }

    /**
     * Full update for one ticker: market data, dividends, splits, statistics and
     * both price series in a single provider call. Used when a ticker is watched
     * but not held, so nothing has ever fetched it.
     */
    public ResponseEntity<String> refreshFull(String ticker) {
        return postTicker("/update/full", ticker);
    }

    /**
     * Backfill the ticker's sharesOutstandingHistory from SEC EDGAR filings.
     * US-registered issuers only; others come back as {"status":"no_data"}.
     */
    public ResponseEntity<String> refreshSharesHistory(String ticker) {
        return postTicker("/update/sharesOutstandingHistory", ticker);
    }

    private ResponseEntity<String> postTicker(String uri, String ticker) {
        Map<String, String> body = new HashMap<>();
        body.put("ticker", ticker);
        return webClient.post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .toEntity(String.class)
                .block(Duration.ofSeconds(35));
    }
}
