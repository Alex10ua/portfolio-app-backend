package com.dev.alex.Service;

import com.dev.alex.Model.Enums.Assets;
import com.dev.alex.Model.Enums.TransactionType;
import com.dev.alex.Model.Holdings;
import com.dev.alex.Model.MarketData;
import com.dev.alex.Model.NonDbModel.Splits;
import com.dev.alex.Model.Portfolios;
import com.dev.alex.Model.Transactions;
import com.dev.alex.Repository.HoldingsRepository;
import com.dev.alex.Repository.MarketDataRepository;
import com.dev.alex.Repository.PortfolioRepository;
import com.dev.alex.Service.Interface.HoldingsService;
import com.dev.alex.Service.WebCalls.FlaskClientService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Transactional
@Service
public class HoldingServiceImpl implements HoldingsService {
    private static final MathContext MATH_CONTEXT = MathContext.DECIMAL128;

    @Autowired
    private HoldingsRepository holdingsRepository;
    @Autowired
    private TransactionServiceImpl transactionService;
    @Autowired
    private MarketDataRepository marketDataRepository;
    @Autowired
    private TickersServiceImpl tickersService;
    @Autowired
    private FlaskClientService flaskClientService;
    @Autowired
    private PortfolioRepository portfolioRepository;
    @Autowired
    private ClosedPositionCleanupService closedPositionCleanupService;
    @Autowired
    private PortfolioAccounting portfolioAccounting;

    /**
     * The one way a holding row goes away: a closed position also loses its allocation target
     * and tags, so every path that drops a holding must come through here.
     */
    @Override
    public void removeHolding(Holdings holding) {
        if (holding == null) return;
        holdingsRepository.delete(holding);
        closedPositionCleanupService.onPositionClosed(holding.getPortfolioId(), holding.getTicker());
    }

    @Override
    public List<Holdings> getAllHoldingsByPortfolioId(String portfolioId) {
       return getPortfolioHoldingsSnapshot(portfolioId).holdings();
    }

    public record HoldingsSnapshot(List<Holdings> holdings, Map<String, MarketData> marketData) {}

    /**
     * A provider refresh can discover a split without a transaction write. Rebuild positions
     * from one transaction read and one projected market-data read, without writing on GET.
     * Stored holding rows supply descriptive metadata only.
     */
    public HoldingsSnapshot getPortfolioHoldingsSnapshot(String portfolioId) {
        LocalDate today = LocalDate.now();
        List<Transactions> transactions = transactionService.findAllByPortfolioId(portfolioId);
        List<Holdings> stored = holdingsRepository.findAllByPortfolioId(portfolioId);
        Map<String, Holdings> storedByTicker = new HashMap<>();
        for (Holdings holding : stored) {
            if (holding.getTicker() != null) storedByTicker.putIfAbsent(holding.getTicker().toUpperCase(Locale.ROOT), holding);
        }
        Map<String, Transactions> metadata = new LinkedHashMap<>();
        for (Transactions tx : transactions) {
            if (tx.getTicker() != null && !tx.getTicker().isBlank()
                    && (tx.getTransactionType() == TransactionType.BUY || tx.getTransactionType() == TransactionType.SELL)
                    && tx.getDate() != null && !tx.getDate().isAfter(today)) {
                metadata.putIfAbsent(tx.getTicker().toUpperCase(Locale.ROOT), tx);
            }
        }
        List<MarketData> marketRows = metadata.isEmpty() ? List.of()
                : marketDataRepository.findByTickerInForHoldingsPage(metadata.keySet());
        Map<String, MarketData> marketByTicker = new HashMap<>();
        Map<String, List<Splits>> splits = new HashMap<>();
        for (MarketData row : marketRows) {
            if (row.getTicker() == null) continue;
            String ticker = row.getTicker().toUpperCase(Locale.ROOT);
            marketByTicker.put(ticker, row);
            if (row.getSplits() != null) splits.put(ticker, row.getSplits());
        }
        PortfolioAccounting.Result accounting = portfolioAccounting.calculate(transactions, splits, today);
        List<Holdings> derived = new ArrayList<>();
        accounting.positions().forEach((ticker, position) -> {
            Transactions tx = metadata.get(ticker);
            if (tx == null) return;
            Holdings source = storedByTicker.get(ticker);
            Holdings holding = new Holdings();
            holding.setHoldingId(source == null ? null : source.getHoldingId());
            holding.setPortfolioId(portfolioId);
            holding.setTicker(ticker);
            holding.setAssetType(tx.getAssetType() != null ? tx.getAssetType()
                    : source != null && source.getAssetType() != null ? source.getAssetType() : Assets.STOCK);
            holding.setName(source != null ? source.getName() : tx.getName());
            holding.setPriceNow(source != null ? source.getPriceNow() : tx.getPriceNow());
            holding.setCreatedAt(source != null ? source.getCreatedAt() : tx.getDate());
            holding.setUpdatedAt(source != null ? source.getUpdatedAt() : tx.getDate());
            holding.setCurrency(position.currency() != null ? position.currency()
                    : source != null && source.getCurrency() != null ? source.getCurrency() : tx.getCurrency());
            holding.setQuantity(position.quantity());
            holding.setAveragePurchasePrice(position.costBasis() == null ? BigDecimal.ZERO
                    : position.costBasis().divide(position.quantity(), MATH_CONTEXT));
            holding.setOpenCostBasis(position.costBasis());
            holding.setCommissions(position.commissions());
            holding.setCostBasisByCurrency(position.costBasisByCurrency());
            holding.setCommissionsByCurrency(position.commissionsByCurrency());
            derived.add(holding);
        });
        return new HoldingsSnapshot(derived, marketByTicker);
    }

    @Override
    public Holdings findHoldingByPortfolioIdAndTicker(String portfolioId, String tickerSymbol) {
        return holdingsRepository.findByPortfolioIdAndTicker(portfolioId, tickerSymbol.toUpperCase());
    }

    /**
     * @return true when this call fetched market data from the provider (the ticker had none), so
     *         the caller need not trigger another refresh for the same ticker.
     */
    @Override
    public boolean updateOrCreateHoldingInPortfolioUpdated(String portfolioId, Transactions newTransaction) {
        Holdings holding = findHoldingByPortfolioIdAndTicker(portfolioId, newTransaction.getTicker().toUpperCase());
        log.info("Inside updateOrCreateHoldingInPortfolioUpdated");
        MarketData marketDataCheck = marketDataRepository.findByTicker(newTransaction.getTicker().toUpperCase());
        //tickersService.saveIfNotExists(newTransaction.getTicker().toUpperCase());// TODO Table for updating ticker price
        Portfolios portfolio = portfolioRepository.findByPortfolioId(portfolioId);
        if (portfolio.getFirstTradeYear() == null) {
            portfolio.setFirstTradeYear(LocalDate.of(newTransaction.getDate().getYear(), 1, 2)); // TODO fix UTC time in mongoDB
            portfolio.setUpdatedAt(new java.util.Date()); // Optionally update the updatedAt field
            portfolioRepository.save(portfolio);
        } else if (portfolio.getFirstTradeYear().isAfter(newTransaction.getDate())) {
            portfolio.setFirstTradeYear(LocalDate.of(newTransaction.getDate().getYear(), 1, 2)); // TODO fix UTC time in mongoDB
            portfolio.setUpdatedAt(new java.util.Date()); // Optionally update the updatedAt field
            portfolioRepository.save(portfolio);
        }
        // null price also triggers a fetch: a stub doc (e.g. created by the custom
        // holding path or a failed provider run) must not block retries forever
        boolean fetched = false;
        if (marketDataCheck == null || marketDataCheck.getPrice() == null) {
            fetched = true;
            try{
                tickersService.createTicker(newTransaction.getTicker().toUpperCase());
                ResponseEntity<String> response = flaskClientService.sendSyncPostRequest(
                        newTransaction.getTicker().toUpperCase(),
                        newTransaction.getAssetType() != null ? newTransaction.getAssetType().name() : null);

                if (response != null) {
                    log.info("Status Code: " + response.getStatusCode());
                    log.info("Response Body: " + response.getBody());
                } else {
                    log.error("No response received");
                }
            } catch (Exception e) {
               log.error("Error in sync call to flask server: " + e.getMessage());
            }
            // Read again: the splits the fetch just stored have to reach this first accumulate, or a
            // BUY dated before a split books the pre-split share count until the next write.
            marketDataCheck = marketDataRepository.findByTicker(newTransaction.getTicker().toUpperCase());
        }

        if (holding == null) {
            Holdings newHolding = new Holdings();
            newHolding.setHoldingId(UUID.randomUUID().toString());
            newHolding.setPortfolioId(portfolioId);
            newHolding.setAssetType(newTransaction.getAssetType());
            newHolding.setTicker(newTransaction.getTicker().toUpperCase());
            newHolding.setQuantity(newTransaction.getQuantity());
            newHolding.setAveragePurchasePrice(BigDecimal.ZERO);
            newHolding.setCurrency(newTransaction.getCurrency());
            newHolding.setCreatedAt(newTransaction.getDate());
            newHolding.setUpdatedAt(newTransaction.getDate());
            holdingsRepository.save(newHolding);

            holding = newHolding;
        }

        List<Transactions> transactionsList =
                transactionService.findAllByPortfolioIdAndTicker(portfolioId, newTransaction.getTicker().toUpperCase());
        transactionsList.sort(Comparator.comparing(Transactions::getDate, Comparator.nullsLast(Comparator.naturalOrder())));
        List<Splits> splitsList = (marketDataCheck != null) ? marketDataCheck.getSplits() : null;
        Position position = accumulate(transactionsList, splitsList);
        BigDecimal totalShares   = position.totalShares();
        BigDecimal finalAvgPrice = position.avgPrice();

        holding.setQuantity(totalShares);
        holding.setAveragePurchasePrice(finalAvgPrice);
        holding.setUpdatedAt(newTransaction.getDate());

        holdingsRepository.save(holding);
        // remove if 0 shares
        holding = findHoldingByPortfolioIdAndTicker(portfolioId, newTransaction.getTicker().toUpperCase());
        if (holding.getQuantity().compareTo(BigDecimal.ZERO) == 0) {
            removeHolding(holding);
        }
        return fetched;
    }

    @Override
    public void recalculateOrCreateHoldingFromTicker(String portfolioId, String ticker, Assets assetType) {
        String upperTicker = ticker.toUpperCase();
        List<Transactions> transactionsList =
                transactionService.findAllByPortfolioIdAndTicker(portfolioId, upperTicker);
        if (transactionsList == null || transactionsList.isEmpty()) return;

        transactionsList.sort(Comparator.comparing(Transactions::getDate, Comparator.nullsLast(Comparator.naturalOrder())));
        LocalDate earliestDate = transactionsList.get(0).getDate();
        LocalDate latestDate   = transactionsList.get(transactionsList.size() - 1).getDate();

        Portfolios portfolio = portfolioRepository.findByPortfolioId(portfolioId);
        if (portfolio.getFirstTradeYear() == null || portfolio.getFirstTradeYear().isAfter(earliestDate)) {
            portfolio.setFirstTradeYear(LocalDate.of(earliestDate.getYear(), 1, 2));
            portfolio.setUpdatedAt(new java.util.Date());
            portfolioRepository.save(portfolio);
        }

        MarketData marketData = marketDataRepository.findByTicker(upperTicker);
        if (marketData == null || marketData.getPrice() == null) {
            try {
                tickersService.createTicker(upperTicker);
                ResponseEntity<String> response = flaskClientService.sendSyncPostRequest(
                        upperTicker, assetType != null ? assetType.name() : null);
                if (response != null) {
                    log.info("Market data fetched for {}: {}", upperTicker, response.getStatusCode());
                } else {
                    log.error("No response received for {}", upperTicker);
                }
            } catch (Exception e) {
                log.error("Error fetching market data for {}: {}", upperTicker, e.getMessage());
            }
            marketData = marketDataRepository.findByTicker(upperTicker);
        }

        Position position = accumulate(transactionsList, marketData != null ? marketData.getSplits() : null);
        BigDecimal totalShares   = position.totalShares();
        BigDecimal finalAvgPrice = position.avgPrice();

        String derivedCurrency = transactionsList.stream()
                .filter(tx -> tx.getTransactionType() == TransactionType.BUY && tx.getCurrency() != null)
                .findFirst()
                .map(Transactions::getCurrency)
                .orElse(null);

        Holdings holding = findHoldingByPortfolioIdAndTicker(portfolioId, upperTicker);
        if (holding == null) {
            holding = new Holdings();
            holding.setHoldingId(UUID.randomUUID().toString());
            holding.setPortfolioId(portfolioId);
            holding.setAssetType(assetType);
            holding.setTicker(upperTicker);
            holding.setCurrency(derivedCurrency);
            holding.setCreatedAt(earliestDate);
        } else if (holding.getCurrency() == null) {
            holding.setCurrency(derivedCurrency);
        }
        holding.setQuantity(totalShares);
        holding.setAveragePurchasePrice(finalAvgPrice);
        holding.setUpdatedAt(latestDate);
        holdingsRepository.save(holding);

        if (totalShares.compareTo(BigDecimal.ZERO) == 0) {
            removeHolding(holding);
        }
    }

    @Override
    public void recalculateOrCreateCustomHoldingFromTicker(String portfolioId, String ticker, Assets assetType) {
        String upperTicker = ticker.toUpperCase();
        List<Transactions> transactionsList =
                transactionService.findAllByPortfolioIdAndTicker(portfolioId, upperTicker);
        if (transactionsList == null || transactionsList.isEmpty()) return;

        transactionsList.sort(Comparator.comparing(Transactions::getDate, Comparator.nullsLast(Comparator.naturalOrder())));
        LocalDate earliestDate = transactionsList.get(0).getDate();
        LocalDate latestDate   = transactionsList.get(transactionsList.size() - 1).getDate();
        Transactions latestTx  = transactionsList.get(transactionsList.size() - 1);

        Portfolios portfolio = portfolioRepository.findByPortfolioId(portfolioId);
        if (portfolio.getFirstTradeYear() == null || portfolio.getFirstTradeYear().isAfter(earliestDate)) {
            portfolio.setFirstTradeYear(LocalDate.of(earliestDate.getYear(), 1, 2));
            portfolio.setUpdatedAt(new java.util.Date());
            portfolioRepository.save(portfolio);
        }

        Position position = accumulate(transactionsList, null); // custom assets have no splits
        BigDecimal totalShares   = position.totalShares();
        BigDecimal finalAvgPrice = position.avgPrice();

        String derivedCurrencyCustom = transactionsList.stream()
                .filter(tx -> tx.getTransactionType() == TransactionType.BUY && tx.getCurrency() != null)
                .findFirst()
                .map(Transactions::getCurrency)
                .orElse(null);

        Holdings holding = findHoldingByPortfolioIdAndTicker(portfolioId, upperTicker);
        if (holding == null) {
            holding = new Holdings();
            holding.setHoldingId(UUID.randomUUID().toString());
            holding.setPortfolioId(portfolioId);
            holding.setAssetType(assetType);
            holding.setTicker(upperTicker);
            holding.setName(latestTx.getName());
            holding.setPriceNow(latestTx.getPriceNow() != null ? latestTx.getPriceNow() : BigDecimal.ZERO);
            holding.setCurrency(derivedCurrencyCustom);
            holding.setCreatedAt(earliestDate);
        } else if (holding.getCurrency() == null) {
            holding.setCurrency(derivedCurrencyCustom);
        }
        holding.setQuantity(totalShares);
        holding.setAveragePurchasePrice(finalAvgPrice);
        holding.setUpdatedAt(latestDate);
        holdingsRepository.save(holding);

        MarketData marketData = marketDataRepository.findByTicker(upperTicker);
        if (marketData == null) {
            MarketData newMarketData = new MarketData();
            newMarketData.setTicker(upperTicker);
            newMarketData.setName(latestTx.getName());
            newMarketData.setPrice(latestTx.getPriceNow());
            newMarketData.setUpdatedAt(latestDate);
            marketDataRepository.save(newMarketData);
        }

        if (totalShares.compareTo(BigDecimal.ZERO) == 0) {
            removeHolding(holding);
        }
    }

    @Override
    public void updateOrCreateCustomHoldingInPortfolio(String portfolioId, Transactions newTransaction) {
        Holdings holdings = findHoldingByPortfolioIdAndTicker(portfolioId, newTransaction.getTicker().toUpperCase());
        Portfolios portfolio = portfolioRepository.findByPortfolioId(portfolioId);
        if (portfolio.getFirstTradeYear() == null) {
            portfolio.setFirstTradeYear(LocalDate.of(newTransaction.getDate().getYear(), 1, 2)); // TODO fix UTC time in mongoDB
            portfolio.setUpdatedAt(new java.util.Date()); // Optionally update the updatedAt field
            portfolioRepository.save(portfolio);
        } else if (portfolio.getFirstTradeYear().isAfter(newTransaction.getDate())) {
            portfolio.setFirstTradeYear(LocalDate.of(newTransaction.getDate().getYear(), 1, 2)); // TODO fix UTC time in mongoDB
            portfolio.setUpdatedAt(new java.util.Date()); // Optionally update the updatedAt field
            portfolioRepository.save(portfolio);
        }
        if (holdings == null){
            Holdings newHolding = new Holdings();
            newHolding.setHoldingId(UUID.randomUUID().toString());
            newHolding.setPortfolioId(portfolioId);
            newHolding.setAssetType(newTransaction.getAssetType());
            newHolding.setTicker(newTransaction.getTicker().toUpperCase());
            newHolding.setName(newTransaction.getName());
            newHolding.setQuantity(newTransaction.getQuantity());
            newHolding.setAveragePurchasePrice(BigDecimal.ZERO);
            newHolding.setPriceNow(newTransaction.getPriceNow() != null ? newTransaction.getPriceNow() : BigDecimal.ZERO);
            newHolding.setCurrency(newTransaction.getCurrency());
            newHolding.setCreatedAt(newTransaction.getDate());
            newHolding.setUpdatedAt(newTransaction.getDate());
            holdingsRepository.save(newHolding);
            holdings = newHolding;
        }

        List<Transactions> transactionsList = transactionService.findAllByPortfolioIdAndTicker(portfolioId, newTransaction.getTicker().toUpperCase());
        transactionsList.sort(Comparator.comparing(Transactions::getDate, Comparator.nullsLast(Comparator.naturalOrder())));
        Position position = accumulate(transactionsList, null); // custom assets have no splits
        BigDecimal totalShares   = position.totalShares();
        BigDecimal finalAvgPrice = position.avgPrice();

        holdings.setQuantity(totalShares);
        holdings.setAveragePurchasePrice(finalAvgPrice);
        holdings.setUpdatedAt(newTransaction.getDate());
        holdingsRepository.save(holdings);
        //MarketData creation for custom holdings
        MarketData marketData = marketDataRepository.findByTicker(newTransaction.getTicker().toUpperCase());
        if (marketData == null){
            MarketData newMarketData = new MarketData();
            newMarketData.setTicker(newTransaction.getTicker().toUpperCase());
            newMarketData.setName(newTransaction.getName());
            newMarketData.setPrice(newTransaction.getPriceNow());
            newMarketData.setUpdatedAt(newTransaction.getDate());
            marketDataRepository.save(newMarketData);
        }

        // remove if 0 shares
        Holdings holdingZero = findHoldingByPortfolioIdAndTicker(portfolioId, newTransaction.getTicker().toUpperCase());
        if (holdingZero.getQuantity().compareTo(BigDecimal.ZERO) == 0) {
            removeHolding(holdingZero);
        }
    }
    @Override
    public void recalculateHoldingFromTransactions(String portfolioId, String ticker) {
        if (ticker == null || ticker.isBlank()) return;
        // Tickers are stored uppercase (the create path uppercases before saving), so a raw
        // ticker straight off a request body misses both lookups and used to 500 the edit.
        String upperTicker = ticker.toUpperCase();

        MarketData marketData = marketDataRepository.findByTicker(upperTicker);
        List<Transactions> transactions =
                transactionService.findAllByPortfolioIdAndTicker(portfolioId, upperTicker);
        if (transactions == null) transactions = Collections.emptyList();
        // accumulate() applies each split to the transactions dated before it, so the replay has
        // to be chronological; the repository returns insertion order.
        transactions = transactions.stream()
                .sorted(Comparator.comparing(Transactions::getDate,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        Position position = accumulate(transactions, marketData != null ? marketData.getSplits() : null);
        BigDecimal totalShares = position.totalShares();
        BigDecimal avgPrice    = position.avgPrice();

        Holdings holding = holdingsRepository.findByPortfolioIdAndTicker(portfolioId, upperTicker);

        if (holding == null) {
            // Nothing left to hold and nothing to update — an edit that zeroed out a position
            // whose holding was already removed is not an error.
            if (totalShares.compareTo(BigDecimal.ZERO) == 0) return;
            holding = new Holdings();
            holding.setHoldingId(UUID.randomUUID().toString());
            holding.setPortfolioId(portfolioId);
            holding.setTicker(upperTicker);
            holding.setAssetType(transactions.stream()
                    .map(Transactions::getAssetType).filter(Objects::nonNull)
                    .reduce((first, second) -> second).orElse(null));
            holding.setCurrency(transactions.stream()
                    .filter(tx -> tx.getTransactionType() == TransactionType.BUY && tx.getCurrency() != null)
                    .findFirst().map(Transactions::getCurrency).orElse(null));
            holding.setCreatedAt(transactions.isEmpty() ? LocalDate.now() : transactions.get(0).getDate());
        }

        holding.setQuantity(totalShares);
        holding.setAveragePurchasePrice(avgPrice);
        holding.setUpdatedAt(LocalDate.now());
        holdingsRepository.save(holding);

        // Every other recalc path drops a zeroed-out holding; without this an edit that sells the
        // last share leaves a 0-share row on the dashboard forever.
        if (totalShares.compareTo(BigDecimal.ZERO) == 0) {
            removeHolding(holding);
            log.info("Holding removed (0 shares) for portfolioId: {}, ticker: {}", portfolioId, upperTicker);
            return;
        }
        log.info("Holding recalculated for portfolioId: {}, ticker: {}", portfolioId, upperTicker);
    }

    /** Net position after replaying a ticker's transactions in chronological order. */
    private record Position(BigDecimal totalShares, BigDecimal avgPrice) {}

    /**
     * Stored averagePurchasePrice now means remaining FIFO cost per share, including BUY fees.
     * The same calculator serves the read snapshot and realized gains, so their bases agree.
     */
    private Position accumulate(List<Transactions> transactionsList, List<Splits> splitsList) {
        Map<String, List<Splits>> splitsByTicker = new HashMap<>();
        if (splitsList != null) for (Transactions tx : transactionsList) {
            if (tx.getTicker() != null) splitsByTicker.put(tx.getTicker().toUpperCase(Locale.ROOT), splitsList);
        }
        PortfolioAccounting.Result result = portfolioAccounting.calculate(transactionsList, splitsByTicker, LocalDate.now());
        if (result.positions().isEmpty()) return new Position(BigDecimal.ZERO, BigDecimal.ZERO);
        PortfolioAccounting.Position position = result.positions().values().iterator().next();
        return new Position(position.quantity(), position.costBasis() == null ? BigDecimal.ZERO
                : position.costBasis().divide(position.quantity(), MATH_CONTEXT));
    }

}
