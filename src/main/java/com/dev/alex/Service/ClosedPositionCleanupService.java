package com.dev.alex.Service;

import com.dev.alex.Model.Holdings;
import com.dev.alex.Model.Portfolios;
import com.dev.alex.Model.UserSettings;
import com.dev.alex.Repository.HoldingsRepository;
import com.dev.alex.Repository.PortfolioRepository;
import com.dev.alex.Repository.TickerTagsRepository;
import com.dev.alex.Repository.UserSettingsRepository;
import com.dev.alex.Repository.WatchlistRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Drops the per-ticker user intent that only makes sense while a position is held: its
 * allocation target and its tags. Called whenever a holding row is deleted (sold out, last
 * BUY/SELL removed, import batch deleted).
 */
@Slf4j
@Service
public class ClosedPositionCleanupService {

    @Autowired
    private TickerTagsRepository tickerTagsRepository;
    @Autowired
    private WatchlistRepository watchlistRepository;
    @Autowired
    private PortfolioRepository portfolioRepository;
    @Autowired
    private UserSettingsRepository userSettingsRepository;
    @Autowired
    private HoldingsRepository holdingsRepository;

    /** Never throws — the holding write that triggered it has already happened. */
    public void onPositionClosed(String portfolioId, String ticker) {
        if (portfolioId == null || ticker == null) return;
        String upperTicker = ticker.toUpperCase();
        try {
            // A watched ticker keeps its tags: they drive the Watchlist's filter chips.
            if (watchlistRepository.findByPortfolioIdAndTicker(portfolioId, upperTicker).isEmpty()) {
                tickerTagsRepository.deleteAllByPortfolioIdAndTicker(portfolioId, upperTicker);
            }
            removeTarget(portfolioId, upperTicker);
            log.info("Closed position cleanup for portfolioId: {}, ticker: {}", portfolioId, upperTicker);
        } catch (Exception e) {
            log.warn("Closed position cleanup failed for portfolioId: {}, ticker: {}", portfolioId, upperTicker, e);
        }
    }

    /**
     * Strips targets on tickers the portfolio no longer holds. {@code PUT /users/me/settings}
     * replaces the whole document, so a client still holding a pre-sale copy would otherwise
     * write the removed target straight back.
     */
    public void dropTargetsForUnheldTickers(UserSettings settings) {
        if (settings == null || settings.getPortfolioSettings() == null) return;
        for (Map.Entry<String, UserSettings.PortfolioSettings> entry : settings.getPortfolioSettings().entrySet()) {
            UserSettings.PortfolioSettings ps = entry.getValue();
            if (ps == null || ps.getTargets() == null || ps.getTargets().isEmpty()) continue;
            Set<String> held = holdingsRepository.findAllByPortfolioId(entry.getKey()).stream()
                    .map(Holdings::getTicker)
                    .filter(t -> t != null)
                    .map(String::toUpperCase)
                    .collect(Collectors.toSet());
            ps.setTargets(ps.getTargets().stream()
                    .filter(t -> t.getTicker() != null && held.contains(t.getTicker().toUpperCase()))
                    .collect(Collectors.toList()));
        }
    }

    private void removeTarget(String portfolioId, String upperTicker) {
        Portfolios portfolio = portfolioRepository.findByPortfolioId(portfolioId);
        if (portfolio == null || portfolio.getUsername() == null) return;
        UserSettings settings = userSettingsRepository.findById(portfolio.getUsername()).orElse(null);
        if (settings == null || settings.getPortfolioSettings() == null) return;
        UserSettings.PortfolioSettings ps = settings.getPortfolioSettings().get(portfolioId);
        if (ps == null || ps.getTargets() == null) return;

        List<UserSettings.AllocationTarget> kept = ps.getTargets().stream()
                .filter(t -> t.getTicker() == null || !t.getTicker().equalsIgnoreCase(upperTicker))
                .collect(Collectors.toList());
        if (kept.size() == ps.getTargets().size()) return;
        ps.setTargets(kept);
        settings.setUpdatedAt(new java.util.Date());
        userSettingsRepository.save(settings);
    }
}
