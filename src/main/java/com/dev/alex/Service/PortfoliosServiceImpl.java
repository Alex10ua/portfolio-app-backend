package com.dev.alex.Service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dev.alex.Model.CustomAsset;
import com.dev.alex.Repository.CashHoldingRepository;
import com.dev.alex.Repository.CustomAssetRepository;
import com.dev.alex.Repository.HoldingsRepository;
import com.dev.alex.Repository.ImportBatchRepository;
import com.dev.alex.Repository.MarketDataRepository;
import com.dev.alex.Repository.PortfolioRepository;
import com.dev.alex.Repository.RealizedPnlCacheRepository;
import com.dev.alex.Repository.TickerNotesRepository;
import com.dev.alex.Repository.TickerTagsRepository;
import com.dev.alex.Repository.TransactionsRepository;
import com.dev.alex.Service.Interface.PortfolioService;

import java.util.List;

@Service
@Transactional
public class PortfoliosServiceImpl implements PortfolioService {

    @Autowired
    private PortfolioRepository portfolioRepository;
    @Autowired
    private TransactionsRepository transactionsRepository;
    @Autowired
    private HoldingsRepository holdingsRepository;
    @Autowired
    private CustomAssetRepository customAssetRepository;
    @Autowired
    private CashHoldingRepository cashHoldingRepository;
    @Autowired
    private ImportBatchRepository importBatchRepository;
    @Autowired
    private TickerTagsRepository tickerTagsRepository;
    @Autowired
    private TickerNotesRepository tickerNotesRepository;
    @Autowired
    private RealizedPnlCacheRepository realizedPnlCacheRepository;
    @Autowired
    private MarketDataRepository marketDataRepository;

    /**
     * Deletes the portfolio and every document keyed by its portfolioId:
     * transactions, holdings, custom assets, manual cash, import batches,
     * tags, notes and the realized-P&L cache. Also removes marketData docs
     * of this portfolio's custom tickers when no other portfolio defines the
     * same custom ticker (they hold user-set prices, not provider data).
     * Global collections (marketData of listed tickers, priceHistoryCache,
     * exchangeRates) are shared across portfolios and stay.
     */
    @Override
    public void deletePortfolioCascade(String portfolioId) {
        // before customAssets are gone: clean up their marketData stubs
        List<CustomAsset> customAssets = customAssetRepository.findAllByPortfolioId(portfolioId);
        for (CustomAsset asset : customAssets) {
            if (asset.getTicker() != null
                    && !customAssetRepository.existsByTickerAndPortfolioIdNot(asset.getTicker(), portfolioId)) {
                marketDataRepository.deleteByTicker(asset.getTicker());
            }
        }

        transactionsRepository.deleteAllByPortfolioId(portfolioId);
        holdingsRepository.deleteAllByPortfolioId(portfolioId);
        customAssetRepository.deleteAllByPortfolioId(portfolioId);
        cashHoldingRepository.deleteAllByPortfolioId(portfolioId);
        importBatchRepository.deleteAllByPortfolioId(portfolioId);
        tickerTagsRepository.deleteAllByPortfolioId(portfolioId);
        tickerNotesRepository.deleteAllByPortfolioId(portfolioId);
        realizedPnlCacheRepository.deleteById(portfolioId);
        portfolioRepository.deleteById(portfolioId); // _id == portfolioId
    }
}
