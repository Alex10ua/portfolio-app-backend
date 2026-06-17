package com.dev.alex.Service;

import com.dev.alex.Model.Enums.Assets;
import com.dev.alex.Model.Holdings;
import com.dev.alex.Model.MarketData;
import com.dev.alex.Model.NonDbModel.Dividend;
import com.dev.alex.Model.NonDbModel.DividendInfoCompleteData;
import com.dev.alex.Model.NonDbModel.Splits;
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
        DividendUtils dividendUtils = new DividendUtils();
        List<Map<String, BigDecimal>> divMapList = new ArrayList<>();
        Map<String, List<Dividend>> holdingsDividends = new HashMap<>();
        Map<String, List<Transactions>> holdingsTransactions = new HashMap<>();
        Map<String, List<Splits>> holdingsSplits = new HashMap<>();
        List<String> tickers = new ArrayList<>();
        BigDecimal allYearlyDividends = BigDecimal.ZERO;

        for (Holdings holding : allHolding) {
            // get market data dividends for stock
            if (holding.getAssetType().equals(Assets.STOCK)) {
                MarketData marketData = marketDataService.getMarketDataByTicker((holding.getTicker()));
                if (marketData.getDividends() != null && !marketData.getDividends().isEmpty()) {
                    tickers.add(holding.getTicker());
                    // get from holdings list of transaction for stock in holding
                    List<Transactions> transactionsList = transactionService.findAllByPortfolioIdAndTicker(portfolioId,
                            holding.getTicker());
                    holdingsTransactions.put(holding.getTicker(), transactionsList);// map for calculation of dividends
                                                                                    // by month
                    List<Dividend> dividendList = marketData.getDividends();
                    List<Splits> splitsList = marketData.getSplits();
                    holdingsDividends.put(holding.getTicker(), dividendList);
                    holdingsSplits.put(holding.getTicker(), splitsList);
                    if (marketData.getYearlyDividend() != null) {
                        allYearlyDividends = allYearlyDividends
                                .add(marketData.getYearlyDividend().multiply(holding.getQuantity()));
                    }
                    // move to DividendsUtils
                    BigDecimal divForStock = dividendUtils.calculateAllDividendsByStockAuto(dividendList,
                            transactionsList, splitsList);
                    Map<String, BigDecimal> allDivForStock = new HashMap<>();
                    allDivForStock.put(holding.getTicker(), divForStock);
                    divMapList.add(allDivForStock);
                }
            }
        }
        if (!holdingsDividends.isEmpty()) {
            dividendInfoCompleteData.setTickerAmount(divMapList);
            dividendInfoCompleteData.setAmountByMonth(dividendUtils.calculateDividendsPerMonthAuto(holdingsTransactions,
                    holdingsDividends, holdingsSplits, tickers));
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
        Map<String, List<Dividend>> marketDividendsByTicker = new HashMap<>();
        Map<String, List<Transactions>> transactionsByTickerMap = new HashMap<>(); // Will store sorted transactions
        Map<String, List<Splits>> marketSplitsByTicker = new HashMap<>(); // Will store sorted splits
        List<String> tickersWithDividendData = new ArrayList<>(); // Tickers for which we found dividend market data
        Map<String, BigDecimal> tickerDivisor = new HashMap<>(); // currency normalization (e.g. GBp pence → GBP)

        if (allPortfolioTransactions == null || allPortfolioTransactions.isEmpty()) {
            // No transactions, so no historical dividends. Initialize and return.
            dividendInfoCompleteData.setTickerAmount(receivedDividendsPerTickerList);
            dividendInfoCompleteData.setAmountByMonth(new HashMap<>());
            dividendInfoCompleteData.setDisplayCurrency("USD");
            calculateAndSetYearlyProjection(dividendInfoCompleteData, portfolioId,
                    fxRateService.getRateForCurrency("USD"));
            return dividendInfoCompleteData;
        }

        // 2. Identify unique tickers from transactions
        Set<String> uniqueTickers = allPortfolioTransactions.stream()
                .map(Transactions::getTicker)
                .filter(Objects::nonNull) // Ensure ticker is not null
                .collect(Collectors.toSet());

        // 3. Process each unique ticker.
        // Dividend amounts come from MarketData, so the native currency is the
        // MarketData currency (e.g. "GBp" pence) — NOT the transaction currency
        // (e.g. "GBP" pounds). Collect raw native amounts + per-ticker FX rate first;
        // the display currency / conversion is applied afterwards.
        Map<String, BigDecimal> rawTickerAmount = new HashMap<>(); // native-currency totals
        Map<String, BigDecimal> tickerNativeRate = new HashMap<>(); // rateVsEur of each ticker's native currency
        Set<String> marketCurrencies = new HashSet<>(); // normalized native currencies (GBp/GBx → GBP)

        for (String ticker : uniqueTickers) {
            MarketData marketData = marketDataService.getMarketDataByTicker(ticker);

            if (marketData != null && marketData.getDividends() != null && !marketData.getDividends().isEmpty()) {
                tickersWithDividendData.add(ticker);

                // Filter transactions for the current ticker AND SORT THEM BY DATE
                List<Transactions> currentTickerTransactions = allPortfolioTransactions.stream()
                        .filter(t -> ticker.equals(t.getTicker()))
                        .sorted(Comparator.comparing(Transactions::getDate, Comparator.nullsLast(Comparator.naturalOrder()))) // ESSENTIAL
                        .collect(Collectors.toList());

                transactionsByTickerMap.put(ticker, currentTickerTransactions);
                marketDividendsByTicker.put(ticker, marketData.getDividends());

                String nativeCurrency = marketData.getCurrency();
                tickerNativeRate.put(ticker, safeRate(fxRateService.getRateForCurrency(nativeCurrency)));
                marketCurrencies.add(normalizeCurrency(nativeCurrency));

                // Get splits, ensure non-null, AND SORT THEM BY DATE
                List<Splits> tickerSplits = (marketData.getSplits() == null) ? new ArrayList<>()
                        : new ArrayList<>(marketData.getSplits());
                tickerSplits.sort(Comparator.comparing(Splits::getSplitDate)); // ESSENTIAL
                marketSplitsByTicker.put(ticker, tickerSplits);

                // Total received dividends for this stock in its native currency
                rawTickerAmount.put(ticker, dividendUtils.calculateAllDividendsByStockAuto(
                        marketData.getDividends(),
                        currentTickerTransactions,
                        tickerSplits));
            }
        }

        // Determine display currency: USD if dividend stocks span multiple
        // currencies, otherwise the single currency they pay in.
        String displayCurrency = marketCurrencies.size() == 1
                ? marketCurrencies.iterator().next()
                : "USD";
        dividendInfoCompleteData.setDisplayCurrency(displayCurrency);
        BigDecimal targetRate = safeRate(fxRateService.getRateForCurrency(displayCurrency)); // units of display ccy per 1 EUR

        // Build per-ticker divisor (native → display): amount / (nativeRate / targetRate)
        for (String ticker : tickersWithDividendData) {
            BigDecimal nativeRate = tickerNativeRate.getOrDefault(ticker, BigDecimal.ONE);
            BigDecimal divisor = nativeRate.divide(targetRate, 10, java.math.RoundingMode.HALF_UP);
            tickerDivisor.put(ticker, divisor);

            BigDecimal amount = rawTickerAmount.getOrDefault(ticker, BigDecimal.ZERO);
            if (divisor.compareTo(BigDecimal.ONE) != 0) {
                amount = amount.divide(divisor, 10, java.math.RoundingMode.HALF_UP);
            }
            Map<String, BigDecimal> receivedAmountMap = new HashMap<>();
            receivedAmountMap.put(ticker, amount);
            receivedDividendsPerTickerList.add(receivedAmountMap);
        }

        // 4. Set results in DividendInfoCompleteData
        dividendInfoCompleteData.setTickerAmount(receivedDividendsPerTickerList);

        if (!tickersWithDividendData.isEmpty()) {
            // Calculate dividends received per month using the collected historical data
            // and DividendUtils
            dividendInfoCompleteData.setAmountByMonth(dividendUtils.calculateDividendsPerMonthAuto(
                    transactionsByTickerMap,
                    marketDividendsByTicker,
                    marketSplitsByTicker,
                    tickersWithDividendData,
                    tickerDivisor));
        } else {
            dividendInfoCompleteData.setAmountByMonth(new HashMap<>());
        }

        // 5. Calculate and set yearly dividend projection based on *current* holdings
        calculateAndSetYearlyProjection(dividendInfoCompleteData, portfolioId, targetRate);

        dividendInfoCompleteData.setFxRates(fxRateService.getAllRatesAsMap());
        return dividendInfoCompleteData;
    }

    /** GBp/GBx pence collapse to GBP for display-currency purposes. */
    private String normalizeCurrency(String currency) {
        if (currency == null) return "USD";
        return ("GBp".equals(currency) || "GBx".equals(currency)) ? "GBP" : currency;
    }

    /** Falls back to 1 for null/zero rates so division stays safe. */
    private BigDecimal safeRate(BigDecimal rate) {
        return (rate == null || rate.compareTo(BigDecimal.ZERO) == 0) ? BigDecimal.ONE : rate;
    }

    /**
     * Helper method to calculate and set the yearly dividend projection
     * based on current holdings in the portfolio.
     */
    private void calculateAndSetYearlyProjection(DividendInfoCompleteData dividendInfoCompleteData,
            String portfolioId, BigDecimal targetRate) {
        List<Holdings> currentHoldings = holdingService.getAllHoldingsByPortfolioId(portfolioId);
        BigDecimal yearlyProjection = BigDecimal.ZERO;

        if (currentHoldings == null) {
            dividendInfoCompleteData.setYearlyCombineDividendsProjection(yearlyProjection);
            return;
        }

        Map<String, MarketData> marketDataCache = new HashMap<>();

        for (Holdings holding : currentHoldings) {
            if (Assets.STOCK.equals(holding.getAssetType()) &&
                    holding.getQuantity() != null &&
                    holding.getQuantity().compareTo(BigDecimal.ZERO) > 0) {

                MarketData md = marketDataCache.computeIfAbsent(holding.getTicker(),
                        tickerKey -> marketDataService.getMarketDataByTicker(tickerKey));

                if (md != null && md.getYearlyDividend() != null) {
                    BigDecimal projected = md.getYearlyDividend().multiply(holding.getQuantity());
                    // yearlyDividend is in MarketData currency (e.g. GBp pence), not the holding's.
                    // native → display currency: amount / (nativeRate / targetRate)
                    BigDecimal nativeRate = safeRate(fxRateService.getRateForCurrency(md.getCurrency()));
                    BigDecimal divisor = (targetRate == null || targetRate.compareTo(BigDecimal.ZERO) == 0)
                            ? nativeRate
                            : nativeRate.divide(targetRate, 10, java.math.RoundingMode.HALF_UP);
                    if (divisor.compareTo(BigDecimal.ONE) != 0) {
                        projected = projected.divide(divisor, 10, java.math.RoundingMode.HALF_UP);
                    }
                    yearlyProjection = yearlyProjection.add(projected);
                }
            }
        }
        dividendInfoCompleteData.setYearlyCombineDividendsProjection(yearlyProjection);
    }

}
