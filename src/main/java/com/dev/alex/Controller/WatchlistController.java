package com.dev.alex.Controller;

import com.dev.alex.Model.NonDbModel.TickerSuggestion;
import com.dev.alex.Model.NonDbModel.WatchlistEntry;
import com.dev.alex.Service.Interface.WatchlistService;
import com.dev.alex.Service.PortfolioAccessService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class WatchlistController {

    @Autowired
    private WatchlistService watchlistService;
    @Autowired
    private PortfolioAccessService portfolioAccessService;

    @GetMapping("/{portfolioId}/watchlist")
    public List<WatchlistEntry> getWatchlist(@PathVariable String portfolioId, Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        return watchlistService.getWatchlist(portfolioId);
    }

    /** Suggestions for the add-ticker field: tickers marketData already knows. */
    @GetMapping("/{portfolioId}/watchlist/search")
    public List<TickerSuggestion> search(@PathVariable String portfolioId,
                                         @RequestParam String q,
                                         Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        return watchlistService.search(portfolioId, q);
    }

    /** Yield distribution for a ticker that is not on the list yet; 404 when nothing is stored for it. */
    @GetMapping("/{portfolioId}/watchlist/preview")
    public ResponseEntity<WatchlistEntry> preview(@PathVariable String portfolioId,
                                                  @RequestParam String ticker,
                                                  Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        WatchlistEntry entry = watchlistService.preview(portfolioId, ticker);
        return entry != null ? ResponseEntity.ok(entry) : ResponseEntity.notFound().build();
    }

    /**
     * Add a ticker. An unknown symbol is fetched from the market-data service
     * first, so this call can take a few seconds; omitting targetYield defaults it
     * to the ticker's 5-year 90th-percentile yield.
     */
    @PostMapping("/{portfolioId}/watchlist")
    public ResponseEntity<WatchlistEntry> addTicker(@PathVariable String portfolioId,
                                                    @RequestBody Map<String, Object> body,
                                                    Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        String ticker = body.get("ticker") == null ? null : String.valueOf(body.get("ticker"));
        BigDecimal targetYield = decimal(body.get("targetYield"));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(watchlistService.addTicker(portfolioId, ticker, targetYield));
    }

    @PutMapping("/{portfolioId}/watchlist/{ticker}")
    public ResponseEntity<WatchlistEntry> setTargetYield(@PathVariable String portfolioId,
                                                         @PathVariable String ticker,
                                                         @RequestBody Map<String, Object> body,
                                                         Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        return ResponseEntity.ok(watchlistService.setTargetYield(portfolioId, ticker, decimal(body.get("targetYield"))));
    }

    @DeleteMapping("/{portfolioId}/watchlist/{ticker}")
    public ResponseEntity<Void> removeTicker(@PathVariable String portfolioId,
                                             @PathVariable String ticker,
                                             Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        watchlistService.removeTicker(portfolioId, ticker);
        return ResponseEntity.noContent().build();
    }

    /** Re-pull price, dividends and price history for one watched ticker. */
    @PostMapping("/{portfolioId}/watchlist/{ticker}/refresh")
    public ResponseEntity<WatchlistEntry> refreshTicker(@PathVariable String portfolioId,
                                                        @PathVariable String ticker,
                                                        Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        return ResponseEntity.ok(watchlistService.refreshTicker(portfolioId, ticker));
    }

    private BigDecimal decimal(Object raw) {
        if (raw == null) return null;
        try {
            return new BigDecimal(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + raw + "' is not a number");
        }
    }
}
