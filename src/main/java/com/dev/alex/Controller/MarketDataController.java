package com.dev.alex.Controller;

import com.dev.alex.Model.MarketData;
import com.dev.alex.Service.MarketDataServiceImpl;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@CrossOrigin(origins = "http://localhost:3001") // fix Access-Control-Allow-Origin
@RestController
@RequestMapping("/api/v1")
public class MarketDataController {
    @Autowired
    private MarketDataServiceImpl marketDataService;

    @GetMapping("/market-data/{ticker}")
    public ResponseEntity<MarketData> getMarketDataByTicker(@PathVariable String ticker) {
        MarketData data = marketDataService.getMarketDataForHoldingsPage(ticker.toUpperCase());
        return data != null ? ResponseEntity.ok(data) : ResponseEntity.notFound().build();
    }

    @PutMapping("/{ticker}/update")
    public ResponseEntity<String> updatePriceByTicker(@PathVariable String ticker, BigDecimal price) {
        marketDataService.updatePriceByTicker(ticker.toUpperCase(), price);
        return ResponseEntity.ok("updated");
    }
}
