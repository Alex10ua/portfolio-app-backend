package com.dev.alex.Controller;

import com.dev.alex.Model.Holdings;
import com.dev.alex.Model.ImportBatch;
import com.dev.alex.Model.Transactions;
import com.dev.alex.Model.Enums.Assets;
import com.dev.alex.Repository.HoldingsRepository;
import com.dev.alex.Repository.ImportBatchRepository;
import com.dev.alex.Repository.TransactionsRepository;
import com.dev.alex.Service.HoldingServiceImpl;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1")
@CrossOrigin(origins = "http://localhost:3001")
public class ImportBatchController {

    @Autowired
    private ImportBatchRepository importBatchRepository;
    @Autowired
    private TransactionsRepository transactionsRepository;
    @Autowired
    private HoldingsRepository holdingsRepository;
    @Autowired
    private HoldingServiceImpl holdingService;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ImportBatchRequest {
        private String filename;
        private List<Transactions> transactions;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ImportBatchDetailResponse {
        private ImportBatch batch;
        private List<Transactions> transactions;
    }

    @PostMapping("/{portfolioId}/imports")
    public ResponseEntity<?> createImportBatch(@PathVariable String portfolioId,
                                               @RequestBody ImportBatchRequest request) {
        try {
            String batchId = UUID.randomUUID().toString();

            List<Transactions> transactions = request.getTransactions();
            if (transactions == null || transactions.isEmpty()) {
                return ResponseEntity.badRequest().body("No transactions provided.");
            }

            for (Transactions t : transactions) {
                t.setTransactionId(UUID.randomUUID().toString());
                t.setPortfolioId(portfolioId);
                t.setImportBatchId(batchId);
                if (t.getTicker() != null) {
                    t.setTicker(t.getTicker().toUpperCase());
                }
                if (t.getPrice() != null && t.getQuantity() != null) {
                    t.setTotalAmount(t.getPrice().multiply(t.getQuantity()));
                }
            }

            transactionsRepository.saveAll(transactions);

            ImportBatch batch = new ImportBatch();
            batch.setBatchId(batchId);
            batch.setPortfolioId(portfolioId);
            batch.setFilename(request.getFilename());
            batch.setUploadedAt(LocalDateTime.now());
            batch.setTransactionCount(transactions.size());
            importBatchRepository.save(batch);

            // For each unique STOCK ticker, call updateOrCreateHoldingInPortfolioUpdated once.
            // It creates the holding if needed, recalculates from all transactions,
            // updates firstTradeYear, and triggers market data fetch.
            Map<String, Transactions> firstStockTxByTicker = new LinkedHashMap<>();
            for (Transactions t : transactions) {
                if (t.getAssetType() != null && t.getAssetType().equals(Assets.STOCK)) {
                    firstStockTxByTicker.putIfAbsent(t.getTicker(), t);
                }
            }
            for (Transactions representative : firstStockTxByTicker.values()) {
                holdingService.updateOrCreateHoldingInPortfolioUpdated(portfolioId, representative);
            }

            // For non-stock assets, process each transaction individually
            for (Transactions t : transactions) {
                if (t.getAssetType() != null && !t.getAssetType().equals(Assets.STOCK)) {
                    holdingService.updateOrCreateCustomHoldingInPortfolio(portfolioId, t);
                }
            }

            return ResponseEntity.ok(batch);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    @GetMapping("/{portfolioId}/imports")
    public ResponseEntity<List<ImportBatch>> getImports(@PathVariable String portfolioId) {
        List<ImportBatch> batches = importBatchRepository.findAllByPortfolioIdOrderByUploadedAtDesc(portfolioId);
        return ResponseEntity.ok(batches);
    }

    @GetMapping("/{portfolioId}/imports/{batchId}")
    public ResponseEntity<?> getImportDetail(@PathVariable String portfolioId,
                                             @PathVariable String batchId) {
        Optional<ImportBatch> batch = importBatchRepository.findById(batchId);
        if (batch.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        List<Transactions> transactions = transactionsRepository.findAllByImportBatchId(batchId);
        return ResponseEntity.ok(new ImportBatchDetailResponse(batch.get(), transactions));
    }

    @DeleteMapping("/{portfolioId}/imports/{batchId}")
    public ResponseEntity<Map<String, Boolean>> deleteImportBatch(@PathVariable String portfolioId,
                                                                   @PathVariable String batchId) {
        List<Transactions> batchTransactions = transactionsRepository.findAllByImportBatchId(batchId);

        Set<String> affectedStockTickers = batchTransactions.stream()
                .filter(t -> t.getAssetType() != null && t.getAssetType().equals(Assets.STOCK))
                .map(Transactions::getTicker)
                .collect(Collectors.toSet());

        transactionsRepository.deleteAllByImportBatchId(batchId);
        importBatchRepository.deleteById(batchId);

        for (String ticker : affectedStockTickers) {
            List<Transactions> remaining = transactionsRepository.findAllByPortfolioIdAndTicker(portfolioId, ticker);
            if (remaining.isEmpty()) {
                // No transactions left for this ticker — remove the holding entirely
                Holdings holding = holdingsRepository.findByPortfolioIdAndTicker(portfolioId, ticker);
                if (holding != null) {
                    holdingsRepository.delete(holding);
                }
            } else {
                // Holding exists and still has transactions — recalculate
                holdingService.recalculateHoldingFromTransactions(portfolioId, ticker);
            }
        }

        Map<String, Boolean> response = new HashMap<>();
        response.put("deleted", Boolean.TRUE);
        return ResponseEntity.ok(response);
    }
}
