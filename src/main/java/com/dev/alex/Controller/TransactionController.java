package com.dev.alex.Controller;

import com.dev.alex.Model.CustomAsset;
import com.dev.alex.Model.Enums.Assets;
import com.dev.alex.Model.Enums.TransactionType;
import com.dev.alex.Model.Transactions;
import com.dev.alex.Repository.HoldingsRepository;
import com.dev.alex.Repository.TransactionsRepository;
import com.dev.alex.Service.CustomAssetServiceImpl;
import com.dev.alex.Service.FxRateServiceImpl;
import com.dev.alex.Service.HoldingServiceImpl;
import com.dev.alex.Service.PortfolioAccessService;
import com.dev.alex.Service.PortfolioPerformanceServiceImpl;
import com.dev.alex.Service.TransactionServiceImpl;
import com.dev.alex.Service.TickersServiceImpl;
import com.dev.alex.Service.WebCalls.FlaskClientService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1")
public class TransactionController {
    @Autowired
    private TransactionServiceImpl transactionService;
    @Autowired
    private TransactionsRepository transactionsRepository;
    @Autowired
    private HoldingServiceImpl holdingService;
    @Autowired
    private HoldingsRepository holdingsRepository;
    @Autowired
    private FlaskClientService flaskClientService;
    @Autowired
    private TickersServiceImpl tickersService;
    @Autowired
    private CustomAssetServiceImpl customAssetService;
    @Autowired
    private FxRateServiceImpl fxRateService;
    @Autowired
    private PortfolioAccessService portfolioAccessService;
    @Autowired
    private PortfolioPerformanceServiceImpl performanceService;

    @Operation(summary = "Create Transaction", description = "Create new transaction")
    @ApiResponse(responseCode = "200", description = "Transaction created successfully")
    @PostMapping("/{portfolioId}/createTransaction")
    public ResponseEntity<?> createTransaction(@RequestBody Transactions transaction,
            @PathVariable String portfolioId, Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        transaction.setTransactionId(UUID.randomUUID().toString());
        transaction.setPortfolioId(portfolioId);
        if (transaction.getPrice() != null && transaction.getQuantity() != null) {
            transaction.setTotalAmount(transaction.getPrice().multiply(transaction.getQuantity()));
        }
        if (transaction.getTicker() != null) {
            transaction.setTicker(transaction.getTicker().toUpperCase());
        }

        // Only BUY/SELL change a share count. DIVIDEND/TAX and DEPOSIT/WITHDRAWAL are cash events
        // and never touch a holding — a stock DIVIDEND used to fall through to the custom-asset
        // path, which recomputes the holding without splits and wrote a pre-split share count back.
        Assets assetType = transaction.getAssetType();
        boolean isHoldingChange = transaction.getTransactionType() == TransactionType.BUY
                || transaction.getTransactionType() == TransactionType.SELL;

        // Everything that can reject the request runs before the save. A failure after it used to
        // answer 500 for a transaction that was already stored, and the retry booked it twice.
        if (isHoldingChange) {
            if (transaction.getTicker() == null || transaction.getTicker().isBlank()) {
                throw new IllegalArgumentException("Ticker is required for a " + transaction.getTransactionType() + " transaction");
            }
            if (transaction.getDate() == null) {
                throw new IllegalArgumentException("Date is required for a " + transaction.getTransactionType() + " transaction");
            }
        }
        if (Assets.CUSTOM.equals(assetType) && transaction.getTicker() != null) {
            // Name and priceNow ride on the stored row: a later recalculation rebuilds the holding
            // from the latest transaction, so they must be filled before the save, not after.
            Optional<CustomAsset> definition =
                    customAssetService.findOptionalByPortfolioIdAndTicker(portfolioId, transaction.getTicker());
            if (definition.isEmpty() && isHoldingChange) {
                throw new IllegalArgumentException("No custom asset '" + transaction.getTicker()
                        + "' in this portfolio — create it before recording a transaction");
            }
            definition.ifPresent(customAsset -> {
                if (transaction.getName() == null || transaction.getName().isBlank()) {
                    transaction.setName(customAsset.getName());
                }
                if (transaction.getPriceNow() == null) {
                    transaction.setPriceNow(customAsset.getPriceNow());
                }
            });
        }

        Transactions transactionStatus = transactionsRepository.save(transaction);
        performanceService.evictRealizedPnLCache(portfolioId);

        boolean holdingSynced = true;
        if (isHoldingChange && assetType != null) {
            try {
                if (Assets.STOCK.equals(assetType) || Assets.CRYPTO.equals(assetType)) {
                    // CRYPTO goes through the same market-data flow as STOCK; assetType
                    // tells Flask to use CoinGecko even before the holding exists
                    boolean fetched = holdingService.updateOrCreateHoldingInPortfolioUpdated(portfolioId, transaction);
                    tickersService.saveIfNotExists(transaction.getTicker());
                    if (!fetched) {
                        refreshMarketData(transaction.getTicker(), assetType);
                    }
                } else {
                    // CUSTOM and legacy COIN/FIGURINE/FUND
                    holdingService.updateOrCreateCustomHoldingInPortfolio(portfolioId, transaction);
                }
            } catch (Exception e) {
                // The transaction is committed; the next write to this ticker recalculates the holding.
                holdingSynced = false;
                log.error("Transaction {} saved but its holding update failed for {} in portfolio {}",
                        transactionStatus.getTransactionId(), transaction.getTicker(), portfolioId, e);
            }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("save", transactionStatus);
        response.put("holdingSynced", holdingSynced);
        return ResponseEntity.ok(response);
    }

    /** Best-effort price refresh after a trade in a ticker that already has market data. */
    private void refreshMarketData(String ticker, Assets assetType) {
        try {
            flaskClientService.sendSyncPostRequest(ticker, assetType.name());
        } catch (Exception e) {
            log.warn("Market data refresh failed for {}: {}", ticker, e.getMessage());
        }
    }

    @GetMapping("/{portfolioId}/transactions")
    public List<Transactions> getAllTransactionByPortfolioId(@PathVariable String portfolioId, Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        List<Transactions> transactions = transactionService.findAllByPortfolioId(portfolioId);
        attachFxRates(transactions);
        return transactions;
    }

    @GetMapping("/{portfolioId}/transactions/{year}")
    public List<Transactions> getAllTransactionByPortfolioId(@PathVariable String portfolioId, @PathVariable int year, Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        List<Transactions> transactions = transactionService.findBuySellByPortfolioIdAndYear(portfolioId, year);
        attachFxRates(transactions);
        return transactions;
    }

    /** One read of the rate table for the whole list — a findById per row was N round trips. */
    private void attachFxRates(List<Transactions> transactions) {
        Map<String, BigDecimal> rates = fxRateService.getAllRatesAsMap();
        transactions.forEach(t -> t.setFxRate(fxRateService.getRateForCurrency(t.getCurrency(), rates)));
    }

    @PutMapping("/{portfolioId}/transactions/{transactionId}/update")
    public ResponseEntity<Transactions> updateTransaction(@RequestBody Transactions updatedTransaction,
            @PathVariable String transactionId, @PathVariable String portfolioId, Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        // Read the stored row first: an edit may move the transaction to a different ticker, and
        // the ticker it is leaving has to be recalculated too or its holding keeps the shares.
        Transactions before = transactionsRepository.findById(transactionId)
                .orElseThrow(() -> new RuntimeException("Transaction not found"));
        if (!portfolioId.equals(before.getPortfolioId())) {
            throw new AccessDeniedException("Transaction does not belong to this portfolio");
        }
        String previousTicker = before.getTicker();
        Assets previousAssetType = before.getAssetType();

        Transactions saved = transactionService.updateTransaction(updatedTransaction, transactionId, portfolioId);
        performanceService.evictRealizedPnLCache(portfolioId);

        syncHoldingForTicker(portfolioId, saved.getTicker(), saved.getAssetType());
        if (previousTicker != null && !previousTicker.equalsIgnoreCase(saved.getTicker())) {
            syncHoldingForTicker(portfolioId, previousTicker, previousAssetType);
        }
        return ResponseEntity.ok(saved);
    }

    @GetMapping("/{portfolioId}/cashBalance")
    public ResponseEntity<Map<String, BigDecimal>> getCashBalance(@PathVariable String portfolioId, Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        List<Transactions> transactions = transactionService.findAllByPortfolioId(portfolioId);
        Map<String, BigDecimal> balance = new HashMap<>();
        for (Transactions t : transactions) {
            if (t.getTransactionType() == null || t.getCurrency() == null || t.getAmount() == null) continue;
            String currency = t.getCurrency();
            BigDecimal current = balance.getOrDefault(currency, BigDecimal.ZERO);
            if (t.getTransactionType().equals(TransactionType.DEPOSIT)) {
                balance.put(currency, current.add(t.getAmount()));
            } else if (t.getTransactionType().equals(TransactionType.WITHDRAWAL)) {
                balance.put(currency, current.subtract(t.getAmount()));
            }
        }
        return ResponseEntity.ok(balance);
    }

    @DeleteMapping("/{portfolioId}/transactions/{transactionId}/delete")
    public ResponseEntity<Map<String, Boolean>> deleteTransaction(@PathVariable String portfolioId,
            @PathVariable String transactionId, Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        Transactions transaction = transactionsRepository.findById(transactionId)
                .orElseThrow(() -> new RuntimeException("Transaction not found"));
        if (!portfolioId.equals(transaction.getPortfolioId())) {
            throw new AccessDeniedException("Transaction does not belong to this portfolio");
        }
        transactionsRepository.deleteById(transactionId);
        performanceService.evictRealizedPnLCache(portfolioId);

        // BUY/SELL removal changes share math — bring the holding back in line
        boolean isHoldingChange = transaction.getTransactionType() != null &&
                (transaction.getTransactionType().equals(TransactionType.BUY) ||
                 transaction.getTransactionType().equals(TransactionType.SELL));
        if (isHoldingChange && transaction.getTicker() != null) {
            syncHoldingForTicker(portfolioId, transaction.getTicker(), transaction.getAssetType());
        }
        return ResponseEntity.ok(Map.of("deleted", Boolean.TRUE));
    }

    /**
     * Brings one ticker's holding back in line with whatever transactions remain for it, after a
     * create/edit/delete. A ticker with no BUY/SELL left has no holding at all, so the row is
     * removed rather than recalculated to zero. Never throws — a recalc failure must not fail the
     * write that already committed.
     */
    private void syncHoldingForTicker(String portfolioId, String tickerRaw, Assets assetType) {
        if (tickerRaw == null || tickerRaw.isBlank()) return;
        String ticker = tickerRaw.toUpperCase();
        try {
            List<Transactions> remaining = transactionsRepository.findAllByPortfolioIdAndTicker(portfolioId, ticker);
            boolean anyHoldingChangeLeft = remaining != null && remaining.stream()
                    .anyMatch(t -> t.getTransactionType() == TransactionType.BUY
                            || t.getTransactionType() == TransactionType.SELL);
            if (!anyHoldingChangeLeft) {
                var holding = holdingsRepository.findByPortfolioIdAndTicker(portfolioId, ticker);
                if (holding != null) {
                    holdingService.removeHolding(holding);
                }
            } else if (assetType == null || Assets.STOCK.equals(assetType) || Assets.CRYPTO.equals(assetType)) {
                holdingService.recalculateHoldingFromTransactions(portfolioId, ticker);
            } else {
                // CUSTOM and legacy COIN/FIGURINE/FUND go through the custom recalc
                holdingService.recalculateOrCreateCustomHoldingFromTicker(portfolioId, ticker, assetType);
            }
        } catch (Exception e) {
            log.warn("Error recalculating holding for ticker {}", ticker, e);
        }
    }
}
