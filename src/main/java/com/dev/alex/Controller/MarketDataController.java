package com.dev.alex.Controller;

import com.dev.alex.Model.MarketData;
import com.dev.alex.Model.NonDbModel.MarketStatistics;
import com.dev.alex.Model.NonDbModel.TickerHistoricalData;
import com.dev.alex.Service.MarketDataServiceImpl;
import com.dev.alex.Service.WebCalls.FlaskClientService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Ticker-level market data — not portfolio-scoped, so no assertOwnership (same
 * model as FundamentalsController); gated by the standard session auth.
 */
@RestController
@RequestMapping("/api/v1")
public class MarketDataController {
    @Autowired
    private MarketDataServiceImpl marketDataService;
    @Autowired
    private FlaskClientService flaskClientService;

    @GetMapping("/market-data/{ticker}")
    public ResponseEntity<MarketData> getMarketDataByTicker(@PathVariable String ticker) {
        MarketData data = marketDataService.getMarketDataForHoldingsPage(ticker.toUpperCase());
        return data != null ? ResponseEntity.ok(data) : ResponseEntity.notFound().build();
    }

    /** Yahoo key statistics (Statistics page). 404 until a Yahoo update has run for the ticker. */
    @GetMapping("/market-data/{ticker}/statistics")
    public ResponseEntity<MarketStatistics> getStatistics(@PathVariable String ticker) {
        MarketStatistics stats = marketDataService.getStatistics(ticker.toUpperCase());
        return stats != null ? ResponseEntity.ok(stats) : ResponseEntity.notFound().build();
    }

    /** On-demand statistics refresh: Flask fetches Yahoo .info synchronously, then we re-read Mongo. */
    @PostMapping("/market-data/{ticker}/statistics/refresh")
    public ResponseEntity<?> refreshStatistics(@PathVariable String ticker) {
        String upperTicker = ticker.toUpperCase();
        try {
            flaskClientService.refreshStatistics(upperTicker);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "Fetch from Yahoo Finance failed: " + e.getMessage()));
        }
        MarketStatistics stats = marketDataService.getStatistics(upperTicker);
        return stats != null
                ? ResponseEntity.ok(stats)
                : ResponseEntity.ok(Map.of("status", "no_data", "ticker", upperTicker));
    }

    /** Dividend progression, splits and share-count series for the Historical page. */
    @GetMapping("/market-data/{ticker}/historical")
    public ResponseEntity<TickerHistoricalData> getHistoricalData(@PathVariable String ticker) {
        TickerHistoricalData data = marketDataService.getHistoricalData(ticker.toUpperCase());
        return data != null ? ResponseEntity.ok(data) : ResponseEntity.notFound().build();
    }

    /**
     * Backfill the share-count series from SEC EDGAR (US-registered issuers only),
     * then return the refreshed historical data. Dividends/splits come from the
     * regular Yahoo update, not from here.
     */
    @PostMapping("/market-data/{ticker}/historical/refresh")
    public ResponseEntity<?> refreshHistoricalData(@PathVariable String ticker) {
        String upperTicker = ticker.toUpperCase();
        try {
            flaskClientService.refreshSharesHistory(upperTicker);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "Fetch from SEC EDGAR failed: " + e.getMessage()));
        }
        TickerHistoricalData data = marketDataService.getHistoricalData(upperTicker);
        return data != null
                ? ResponseEntity.ok(data)
                : ResponseEntity.ok(Map.of("status", "no_data", "ticker", upperTicker));
    }

    @PutMapping("/{ticker}/update")
    public ResponseEntity<String> updatePriceByTicker(@PathVariable String ticker, @RequestParam BigDecimal price) {
        marketDataService.updatePriceByTicker(ticker.toUpperCase(), price);
        return ResponseEntity.ok("updated");
    }
}
