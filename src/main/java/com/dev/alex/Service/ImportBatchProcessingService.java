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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ImportBatchProcessingService {

    /** Most tickers named in an error message; the rest are counted. */
    private static final int MAX_LISTED_FAILURES = 10;

    @Autowired
    private HoldingServiceImpl holdingService;
    @Autowired
    private ImportBatchRepository importBatchRepository;

    /**
     * One ticker at a time, each in its own try: a single failure used to abort the whole loop,
     * so every later ticker got no holding row and no market-data fetch. Status is COMPLETED,
     * COMPLETED_WITH_ERRORS (errorMessage names the tickers) or FAILED when none succeeded.
     */
    @Async
    public void processHoldings(String portfolioId, String batchId, List<Transactions> transactions) {
        ImportBatch batch = importBatchRepository.findById(batchId).orElse(null);
        Map<String, Assets> tickerAssetMap = new LinkedHashMap<>();
        for (Transactions t : transactions) {
            boolean isHoldingChange = t.getTransactionType() != null &&
                    (t.getTransactionType().equals(TransactionType.BUY) ||
                     t.getTransactionType().equals(TransactionType.SELL));
            if (isHoldingChange && t.getAssetType() != null && t.getTicker() != null) {
                tickerAssetMap.putIfAbsent(t.getTicker(), t.getAssetType());
            }
        }

        List<String> failed = new ArrayList<>();
        for (Map.Entry<String, Assets> entry : tickerAssetMap.entrySet()) {
            try {
                // CRYPTO is market data like STOCK (CoinGecko prices, a real marketData doc). On the
                // custom path it got no provider fetch and a price-less stub, so an imported coin
                // showed no value until some later write touched it.
                if (entry.getValue().equals(Assets.STOCK) || entry.getValue().equals(Assets.CRYPTO)) {
                    holdingService.recalculateOrCreateHoldingFromTicker(portfolioId, entry.getKey(), entry.getValue());
                } else {
                    holdingService.recalculateOrCreateCustomHoldingFromTicker(portfolioId, entry.getKey(), entry.getValue());
                }
            } catch (Exception e) {
                failed.add(entry.getKey());
                log.error("Batch {}: holding processing failed for {}: {}", batchId, entry.getKey(), e.getMessage(), e);
            }
        }

        if (batch == null) return;
        try {
            if (failed.isEmpty()) {
                batch.setStatus("COMPLETED");
            } else {
                batch.setStatus(failed.size() == tickerAssetMap.size() ? "FAILED" : "COMPLETED_WITH_ERRORS");
                List<String> listed = failed.subList(0, Math.min(failed.size(), MAX_LISTED_FAILURES));
                batch.setErrorMessage("Holding update failed for " + String.join(", ", listed)
                        + (failed.size() > listed.size() ? " and " + (failed.size() - listed.size()) + " more" : "")
                        + ". The transactions are saved; the next write to these tickers retries.");
            }
            importBatchRepository.save(batch);
        } catch (Exception e) {
            log.error("Batch {}: could not record its status", batchId, e);
        }
    }
}
