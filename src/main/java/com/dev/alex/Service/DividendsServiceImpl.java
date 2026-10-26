package com.dev.alex.Service;

import com.dev.alex.Model.Enums.Assets;
import com.dev.alex.Model.Holdings;
import com.dev.alex.Model.MarketData;
import com.dev.alex.Model.NonDbModel.Dividend;
import com.dev.alex.Model.NonDbModel.DividendInfoCompleteData;
import com.dev.alex.Model.Transactions;
import com.dev.alex.Service.Dividends.DividendUtils;
import com.dev.alex.Service.Interface.DividendsService;
import com.dev.alex.Service.Interface.FxRateService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
public class DividendsServiceImpl implements DividendsService {
    private final HoldingServiceImpl holdingService;
    private final MarketDataServiceImpl marketDataService;
    private final TransactionServiceImpl transactionService;
    private final DividendUtils dividendUtils;
    private final FxRateService fxRateService;

    @Autowired
    public DividendsServiceImpl(HoldingServiceImpl holdingService,
            MarketDataServiceImpl marketDataService,
            TransactionServiceImpl transactionService,
            FxRateService fxRateService) {
        this.holdingService = holdingService;
        this.marketDataService = marketDataService;
        this.transactionService = transactionService;
        this.fxRateService = fxRateService;
        this.dividendUtils = new DividendUtils();
    }

    @Deprecated
    @Override
    public DividendInfoCompleteData getAllDividendsInfoByPortfolioId(String portfolioId) {
        // TODO: Implement All time stock dividends only by transaction buy and sell
        DividendInfoCompleteData dividendInfoCompleteData = new DividendInfoCompleteData();
        List<Holdings> allHolding = holdingService.getAllHoldingsByPortfolioId(portfolioId);
        List<Map<String, BigDecimal>> divMapList = new ArrayList<>();
        Map<String, BigDecimal> dividendsByMonth = new TreeMap<>();
        Map<String, List<Transactions>> transactionsByTicker = groupTransactions(
                transactionService.findAllByPortfolioId(portfolioId));
        Map<String, MarketData> marketByTicker = marketDataService.getMarketDataForIncome(
                allHolding.stream().map(Holdings::getTicker).collect(Collectors.toSet()));
        boolean hasDividendData = false;
        BigDecimal allYearlyDividends = BigDecimal.ZERO;

        for (Holdings holding : allHolding) {
            // get market data dividends for stock
            if (holding.getAssetType().equals(Assets.STOCK)) {
                MarketData marketData = marketByTicker.get(holding.getTicker());
                if (marketData != null && marketData.getDividends() != null && !marketData.getDividends().isEmpty()) {
                    hasDividendData = true;
                    if (marketData.getYearlyDividend() != null) {
                        allYearlyDividends = allYearlyDividends
                                .add(marketData.getYearlyDividend().multiply(holding.getQuantity()));
                    }
                    // move to DividendsUtils
                    DividendUtils.DividendTotals totals = dividendUtils.calculateDividends(marketData.getDividends(),
                            transactionsByTicker.get(holding.getTicker()), marketData.getSplits());
                    totals.byMonth().forEach((month, amount) -> dividendsByMonth.merge(month, amount, BigDecimal::add));
                    Map<String, BigDecimal> allDivForStock = new HashMap<>();
                    allDivForStock.put(holding.getTicker(), totals.total());
                    divMapList.add(allDivForStock);
                }
            }
        }
        if (hasDividendData) {
            dividendInfoCompleteData.setTickerAmount(divMapList);
            dividendInfoCompleteData.setAmountByMonth(dividendsByMonth);
            dividendInfoCompleteData.setYearlyCombineDividendsProjection(allYearlyDividends);
        }
        return dividendInfoCompleteData;
    }

    /**
     * Calculates all received dividend information for a portfolio based on its
     * transaction history.
     * This captures dividends from stocks that may have been bought and sold
     * entirely.
     * The yearly projection is still based on *current* holdings.
     *
     * @param portfolioId The ID of the portfolio.
     * @return DividendInfoCompleteData containing historical received dividends and
     *         current projection.
     */
    // If this is replacing the method in DividendsService interface, use @Override
    @Override
    public DividendInfoCompleteData getAllReceivedDividendsInfoFromTransactions(String portfolioId) {
        DividendInfoCompleteData dividendInfoCompleteData = new DividendInfoCompleteData();

        // 1. Fetch all transactions for the portfolio
        List<Transactions> allPortfolioTransactions = transactionService.findAllByPortfolioId(portfolioId);

        List<Map<String, BigDecimal>> receivedDividendsPerTickerList = new ArrayList<>();
        Map<String, List<Transactions>> transactionsByTicker = groupTransactions(allPortfolioTransactions);
        List<Holdings> currentHoldings = holdingService.getAllHoldingsByPortfolioId(portfolioId);
        Set<String> marketTickers = new HashSet<>(transactionsByTicker.keySet());
        if (currentHoldings != null) {
            currentHoldings.stream().filter(h -> Assets.STOCK.equals(h.getAssetType()))
                    .map(Holdings::getTicker).filter(Objects::nonNull).forEach(marketTickers::add);
        }
        // One projected market read serves both the historical income and today's projection.
        Map<String, MarketData> marketByTicker = marketDataService.getMarketDataForIncome(marketTickers);

        if (allPortfolioTransactions == null || allPortfolioTransactions.isEmpty()) {
            // No transactions, so no historical dividends. Initialize and return.
            dividendInfoCompleteData.setTickerAmount(receivedDividendsPerTickerList);
            dividendInfoCompleteData.setTickerCurrency(new HashMap<>());
            dividendInfoCompleteData.setAmountByMonth(new HashMap<>());
            dividendInfoCompleteData.setAmountByMonthByCurrency(new HashMap<>());
            dividendInfoCompleteData.setDisplayCurrency("USD");
            calculateAndSetYearlyProjection(dividendInfoCompleteData, currentHoldings, marketByTicker);
            dividendInfoCompleteData.setFxRates(fxRateService.getAllRatesAsMap());
            return dividendInfoCompleteData;
        }

        // 3. Process each unique ticker.
        // Dividend amounts come from MarketData, so the native currency is the
        // MarketData currency (e.g. "GBp" pence) — NOT the transaction currency
        // (e.g. "GBP" pounds). Amounts are reported in that native currency and
        // never converted here; the client applies FX for the currency it displays.
        Map<String, String> tickerNativeCurrency = new HashMap<>(); // ticker → native currency as quoted
        Set<String> marketCurrencies = new HashSet<>(); // normalized native currencies (GBp/GBx → GBP)
        Map<String, Map<String, BigDecimal>> amountByMonthByCurrency = new HashMap<>();
        Map<String, BigDecimal> amountByMonth = new TreeMap<>();

        for (Map.Entry<String, List<Transactions>> group : transactionsByTicker.entrySet()) {
            String ticker = group.getKey();
            MarketData marketData = marketByTicker.get(ticker);

            if (marketData != null && marketData.getDividends() != null && !marketData.getDividends().isEmpty()) {
                String nativeCurrency = marketData.getCurrency() != null ? marketData.getCurrency() : "USD";
                tickerNativeCurrency.put(ticker, nativeCurrency);
                marketCurrencies.add(normalizeCurrency(nativeCurrency));

                DividendUtils.DividendTotals totals = dividendUtils.calculateDividends(
                        marketData.getDividends(), group.getValue(), marketData.getSplits());
                receivedDividendsPerTickerList.add(Collections.singletonMap(ticker, totals.total()));
                Map<String, BigDecimal> currencyMonths = amountByMonthByCurrency
                        .computeIfAbsent(nativeCurrency, key -> new TreeMap<>());
                totals.byMonth().forEach((month, amount) -> {
                    currencyMonths.merge(month, amount, BigDecimal::add);
                    amountByMonth.merge(month, amount, BigDecimal::add);
                });
            }
        }

        // Hint only: the single currency the payers share, else USD. The client is
        // free to display any currency — it holds the rates and does the maths.
        String displayCurrency = marketCurrencies.size() == 1
                ? marketCurrencies.iterator().next()
                : "USD";
        dividendInfoCompleteData.setDisplayCurrency(displayCurrency);

        // 4. Set results in DividendInfoCompleteData
        dividendInfoCompleteData.setTickerAmount(receivedDividendsPerTickerList);
        dividendInfoCompleteData.setTickerCurrency(tickerNativeCurrency);

        // Each monthly series remains native; the frontend converts before combining currencies.
        dividendInfoCompleteData.setAmountByMonthByCurrency(amountByMonthByCurrency);
        dividendInfoCompleteData.setAmountByMonth(amountByMonth);

        // 5. Calculate and set yearly dividend projection based on *current* holdings
        calculateAndSetYearlyProjection(dividendInfoCompleteData, currentHoldings, marketByTicker);

        dividendInfoCompleteData.setFxRates(fxRateService.getAllRatesAsMap());
        return dividendInfoCompleteData;
    }

    private Map<String, List<Transactions>> groupTransactions(List<Transactions> transactions) {
        if (transactions == null) return Collections.emptyMap();
        return transactions.stream().filter(t -> t != null && t.getTicker() != null)
                .collect(Collectors.groupingBy(Transactions::getTicker));
    }

    /** GBp/GBx pence collapse to GBP for display-currency purposes. */
    private String normalizeCurrency(String currency) {
        if (currency == null) return "USD";
        return ("GBp".equals(currency) || "GBx".equals(currency)) ? "GBP" : currency;
    }

    /**
     * Next-12-months dividend projection from *current* holdings, kept in each
     * ticker's native (MarketData) currency — e.g. a GBp-quoted payer projects in
     * pence. The flat total is the unconverted sum, exact only mono-currency.
     */
    private void calculateAndSetYearlyProjection(DividendInfoCompleteData dividendInfoCompleteData,
            List<Holdings> currentHoldings, Map<String, MarketData> marketByTicker) {
        Map<String, BigDecimal> projectionByCurrency = new HashMap<>();

        if (currentHoldings == null) {
            dividendInfoCompleteData.setProjectionByCurrency(projectionByCurrency);
            dividendInfoCompleteData.setYearlyCombineDividendsProjection(BigDecimal.ZERO);
            return;
        }

        for (Holdings holding : currentHoldings) {
            if (Assets.STOCK.equals(holding.getAssetType()) &&
                    holding.getQuantity() != null &&
                    holding.getQuantity().compareTo(BigDecimal.ZERO) > 0) {

                MarketData md = marketByTicker.get(holding.getTicker());

                if (md != null && md.getYearlyDividend() != null) {
                    BigDecimal projected = md.getYearlyDividend().multiply(holding.getQuantity());
                    String ccy = md.getCurrency() != null ? md.getCurrency() : "USD";
                    projectionByCurrency.merge(ccy, projected, BigDecimal::add);
                }
            }
        }
        dividendInfoCompleteData.setProjectionByCurrency(projectionByCurrency);
        dividendInfoCompleteData.setYearlyCombineDividendsProjection(
                projectionByCurrency.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add));
    }

}
