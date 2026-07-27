package com.dev.alex.Controller;

import com.dev.alex.Model.CompanyFundamentals;
import com.dev.alex.Repository.CompanyFundamentalsRepository;
import com.dev.alex.Service.WebCalls.FlaskClientService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * SEC EDGAR fundamentals — ticker-level data (not portfolio-scoped, like
 * MarketDataController), gated only by the standard session auth.
 */
@RestController
@RequestMapping("/api/v1/fundamentals")
public class FundamentalsController {

    @Autowired
    private CompanyFundamentalsRepository fundamentalsRepository;
    @Autowired
    private FlaskClientService flaskClientService;

    @GetMapping("/{ticker}")
    public ResponseEntity<CompanyFundamentals> getFundamentals(@PathVariable String ticker) {
        return fundamentalsRepository.findById(ticker.toUpperCase())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{ticker}/refresh")
    public ResponseEntity<?> refreshFundamentals(@PathVariable String ticker) {
        String upperTicker = ticker.toUpperCase();
        try {
            flaskClientService.refreshFundamentals(upperTicker);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "Fetch from SEC EDGAR failed: " + e.getMessage()));
        }
        return fundamentalsRepository.findById(upperTicker)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.ok(Map.of("status", "no_data", "ticker", upperTicker)));
    }
}
