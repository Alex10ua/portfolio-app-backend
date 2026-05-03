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

    @Operation(summary = "Create Transaction", description = "Create new transaction")
    @ApiResponse(responseCode = "200", description = "Transaction created successfully")
    @PostMapping("/{portfolioId}/createTransaction")
    public ResponseEntity<?> createTransaction(@RequestBody Transactions transaction,
            @PathVariable String portfolioId, Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        try {
            transaction.setTransactionId(UUID.randomUUID().toString());
            transaction.setPortfolioId(portfolioId);
            if (transaction.getPrice() != null && transaction.getQuantity() != null) {
                transaction.setTotalAmount(transaction.getPrice().multiply(transaction.getQuantity()));
            }
            if (transaction.getTicker() != null) {
                transaction.setTicker(transaction.getTicker().toUpperCase());
            }
            Transactions transactionStatus = transactionsRepository.save(transaction);
            boolean isCash = transaction.getTransactionType() != null &&
                    (transaction.getTransactionType().equals(TransactionType.DEPOSIT) ||
                     transaction.getTransactionType().equals(TransactionType.WITHDRAWAL));
            // DIVIDEND and TAX are cash events, not share quantity changes — skip holding update
            boolean isHoldingChange = transaction.getTransactionType() != null &&
                    (transaction.getTransactionType().equals(TransactionType.BUY) ||
                     transaction.getTransactionType().equals(TransactionType.SELL));
            if (isCash) {
                // Cash deposits and withdrawals do not affect asset holdings
            } else if (isHoldingChange && transaction.getAssetType() != null && transaction.getAssetType().equals(Assets.STOCK)) {
                holdingService.updateOrCreateHoldingInPortfolioUpdated(portfolioId, transaction);
                tickersService.saveIfNotExists(transaction.getTicker());
                flaskClientService.sendSyncPostRequest(transaction.getTicker());
            } else if (transaction.getAssetType() != null && transaction.getAssetType().equals(Assets.CUSTOM)) {
                // Populate name and priceNow from the custom asset definition
                CustomAsset customAsset = customAssetService.findByPortfolioIdAndTicker(portfolioId, transaction.getTicker().toUpperCase());
                if (transaction.getName() == null || transaction.getName().isBlank()) {
                    transaction.setName(customAsset.getName());
                }
                if (transaction.getPriceNow() == null) {
                    transaction.setPriceNow(customAsset.getPriceNow());
                }
                holdingService.updateOrCreateCustomHoldingInPortfolio(portfolioId, transaction);
            } else if (transaction.getAssetType() != null) {
                holdingService.updateOrCreateCustomHoldingInPortfolio(portfolioId, transaction);
            }

            Map<String, Object> response = new HashMap<>();
            response.put("save", transactionStatus);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error creating transaction for portfolio {}", portfolioId, e);
            throw new RuntimeException("Failed to create transaction");
        }
    }

    @GetMapping("/{portfolioId}/transactions")
    public List<Transactions> getAllTransactionByPortfolioId(@PathVariable String portfolioId, Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        List<Transactions> transactions = transactionService.findAllByPortfolioId(portfolioId);
        transactions.forEach(t -> t.setFxRate(fxRateService.getRateForCurrency(t.getCurrency())));
        return transactions;
    }

    @GetMapping("/{portfolioId}/transactions/{year}")
    public List<Transactions> getAllTransactionByPortfolioId(@PathVariable String portfolioId, @PathVariable int year, Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        List<Transactions> transactions = transactionService.findBuySellByPortfolioIdAndYear(portfolioId, year);
        transactions.forEach(t -> t.setFxRate(fxRateService.getRateForCurrency(t.getCurrency())));
        return transactions;
    }

    @PutMapping("/{portfolioId}/transactions/{transactionId}/update")
    public ResponseEntity<Transactions> updateTransaction(@RequestBody Transactions updatedTransaction,
            @PathVariable String transactionId, @PathVariable String portfolioId, Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        transactionService.updateTransaction(updatedTransaction, transactionId, portfolioId);
        // recalculateHoldingFromTransactions works for all asset types:
        // for STOCK it applies split-adjusted calculation; for others splits list is empty (no-op)
        holdingService.recalculateHoldingFromTransactions(portfolioId, updatedTransaction.getTicker());
        return ResponseEntity.ok(updatedTransaction);
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
        return ResponseEntity.ok(Map.of("deleted", Boolean.TRUE));
    }
}
