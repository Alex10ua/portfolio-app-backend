package com.dev.alex.Service.WebCalls;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClientRequest;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Service
public class FlaskClientService {

    /** Ceiling for the ordinary provider calls; matches the WebClient's default response timeout. */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    /**
     * SEC EDGAR calls are paced process-wide on the Flask side (5 s per request by default):
     * fundamentals is ~16 requests ≈ 80 s, shares history ~5 ≈ 25 s, and both queue behind a
     * running bulk backfill. Under the 30 s default every fundamentals load failed with a
     * timeout even though Flask went on to write the document.
     */
    private static final Duration SEC_TIMEOUT = Duration.ofMinutes(5);

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
        return post("/update/auto", body, DEFAULT_TIMEOUT);
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
        return postTicker("/update/fundamentals", ticker, SEC_TIMEOUT);
    }

    /**
     * Refresh only marketData.statistics (Yahoo key statistics) for the ticker.
     * One .info request on the Flask side — no price-history download.
     */
    public ResponseEntity<String> refreshStatistics(String ticker) {
        return postTicker("/update/statistics", ticker, DEFAULT_TIMEOUT);
    }

    /**
     * Full update for one ticker: market data, dividends, splits, statistics and
     * both price series in a single provider call. Used when a ticker is watched
     * but not held, so nothing has ever fetched it.
     */
    public ResponseEntity<String> refreshFull(String ticker) {
        return postTicker("/update/full", ticker, DEFAULT_TIMEOUT);
    }

    /**
     * Backfill the ticker's sharesOutstandingHistory from SEC EDGAR filings.
     * US-registered issuers only; others come back as {"status":"no_data"}.
     */
    public ResponseEntity<String> refreshSharesHistory(String ticker) {
        return postTicker("/update/sharesOutstandingHistory", ticker, SEC_TIMEOUT);
    }

    private ResponseEntity<String> postTicker(String uri, String ticker, Duration timeout) {
        Map<String, String> body = new HashMap<>();
        body.put("ticker", ticker);
        return post(uri, body, timeout);
    }

    /**
     * The response timeout is set per request: the WebClient's own (WebClientConfig) is the
     * default for calls that set nothing, and a longer one here overrides it for this request
     * only. The block() ceiling sits just above it so the Netty timeout, which carries the
     * clearer error, fires first.
     */
    private ResponseEntity<String> post(String uri, Map<String, String> body, Duration timeout) {
        return webClient.post()
                .uri(uri)
                .httpRequest(request -> {
                    Object nativeRequest = request.getNativeRequest();
                    if (nativeRequest instanceof HttpClientRequest reactorRequest) {
                        reactorRequest.responseTimeout(timeout);
                    }
                })
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .toEntity(String.class)
                .block(timeout.plusSeconds(5));
    }
}
