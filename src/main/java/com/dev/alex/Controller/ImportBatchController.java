package com.dev.alex.Controller;

import com.dev.alex.Model.Enums.Assets;
import com.dev.alex.Model.Holdings;
import com.dev.alex.Model.ImportBatch;
import com.dev.alex.Model.Transactions;
import com.dev.alex.Repository.HoldingsRepository;
import com.dev.alex.Repository.ImportBatchRepository;
import com.dev.alex.Repository.TransactionsRepository;
import com.dev.alex.Service.HoldingServiceImpl;
import com.dev.alex.Service.ImportBatchProcessingService;
import com.dev.alex.Service.PortfolioAccessService;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/v1")
public class ImportBatchController {

    @Autowired
    private ImportBatchRepository importBatchRepository;
    @Autowired
    private TransactionsRepository transactionsRepository;
    @Autowired
    private HoldingsRepository holdingsRepository;
    @Autowired
    private HoldingServiceImpl holdingService;
    @Autowired
    private ImportBatchProcessingService importBatchProcessingService;
    @Autowired
    private PortfolioAccessService portfolioAccessService;

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
                                               @RequestBody ImportBatchRequest request,
                                               Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
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
            batch.setStatus("PROCESSING");
            importBatchRepository.save(batch);

            importBatchProcessingService.processHoldings(portfolioId, batchId, transactions);

            return ResponseEntity.accepted().body(batch);
        } catch (Exception e) {
            log.error("Error creating import batch for portfolio {}", portfolioId, e);
            throw new RuntimeException("Failed to create import batch");
        }
    }

    @GetMapping("/{portfolioId}/imports")
    public ResponseEntity<List<ImportBatch>> getImports(@PathVariable String portfolioId, Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        List<ImportBatch> batches = importBatchRepository.findAllByPortfolioIdOrderByUploadedAtDesc(portfolioId);
        return ResponseEntity.ok(batches);
    }

    @GetMapping("/{portfolioId}/imports/{batchId}")
    public ResponseEntity<?> getImportDetail(@PathVariable String portfolioId,
                                             @PathVariable String batchId,
                                             Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        Optional<ImportBatch> batch = importBatchRepository.findById(batchId);
        if (batch.isEmpty() || !portfolioId.equals(batch.get().getPortfolioId())) {
            return ResponseEntity.notFound().build();
        }
        List<Transactions> transactions = transactionsRepository.findAllByImportBatchId(batchId);
        return ResponseEntity.ok(new ImportBatchDetailResponse(batch.get(), transactions));
    }

    @DeleteMapping("/{portfolioId}/imports/{batchId}")
    public ResponseEntity<Map<String, Boolean>> deleteImportBatch(@PathVariable String portfolioId,
                                                                   @PathVariable String batchId,
                                                                   Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        Optional<ImportBatch> batch = importBatchRepository.findById(batchId);
        if (batch.isEmpty() || !portfolioId.equals(batch.get().getPortfolioId())) {
            throw new AccessDeniedException("Batch not found or does not belong to this portfolio");
        }

        List<Transactions> batchTransactions = transactionsRepository.findAllByImportBatchId(batchId);

        Set<String> affectedStockTickers = batchTransactions.stream()
                .filter(t -> t.getAssetType() != null && t.getAssetType().equals(Assets.STOCK))
                .map(Transactions::getTicker)
                .collect(Collectors.toSet());

        transactionsRepository.deleteAllByImportBatchId(batchId);
        importBatchRepository.deleteById(batchId);

        for (String ticker : affectedStockTickers) {
            try {
                List<Transactions> remaining = transactionsRepository.findAllByPortfolioIdAndTicker(portfolioId, ticker);
                if (remaining.isEmpty()) {
                    Holdings holding = holdingsRepository.findByPortfolioIdAndTicker(portfolioId, ticker);
                    if (holding != null) {
                        holdingsRepository.delete(holding);
                    }
                } else {
                    holdingService.recalculateHoldingFromTransactions(portfolioId, ticker);
                }
            } catch (Exception e) {
                log.warn("Error cleaning up holding for ticker {} after batch delete", ticker, e);
            }
        }

        return ResponseEntity.ok(Map.of("deleted", Boolean.TRUE));
    }
}
