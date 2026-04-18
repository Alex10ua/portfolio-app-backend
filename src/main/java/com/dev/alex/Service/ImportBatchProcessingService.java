package com.dev.alex.Service;

import com.dev.alex.Model.Enums.Assets;
import com.dev.alex.Model.Enums.TransactionType;
import com.dev.alex.Model.ImportBatch;
import com.dev.alex.Model.Transactions;
import com.dev.alex.Repository.ImportBatchRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ImportBatchProcessingService {

    @Autowired
    private HoldingServiceImpl holdingService;
    @Autowired
    private ImportBatchRepository importBatchRepository;

    @Async
    public void processHoldings(String portfolioId, String batchId, List<Transactions> transactions) {
        ImportBatch batch = importBatchRepository.findById(batchId).orElse(null);
        try {
            Map<String, Assets> tickerAssetMap = new LinkedHashMap<>();
            for (Transactions t : transactions) {
                boolean isHoldingChange = t.getTransactionType() != null &&
                        (t.getTransactionType().equals(TransactionType.BUY) ||
                         t.getTransactionType().equals(TransactionType.SELL));
                if (isHoldingChange && t.getAssetType() != null && t.getTicker() != null) {
                    tickerAssetMap.putIfAbsent(t.getTicker(), t.getAssetType());
                }
            }

            for (Map.Entry<String, Assets> entry : tickerAssetMap.entrySet()) {
                if (entry.getValue().equals(Assets.STOCK)) {
                    holdingService.recalculateOrCreateHoldingFromTicker(portfolioId, entry.getKey(), entry.getValue());
                } else {
                    holdingService.recalculateOrCreateCustomHoldingFromTicker(portfolioId, entry.getKey(), entry.getValue());
                }
            }

            if (batch != null) {
                batch.setStatus("COMPLETED");
                importBatchRepository.save(batch);
            }
        } catch (Exception e) {
            log.error("Batch {} processing failed: {}", batchId, e.getMessage(), e);
            if (batch != null) {
                batch.setStatus("FAILED");
                batch.setErrorMessage(e.getMessage());
                importBatchRepository.save(batch);
            }
        }
    }
}
