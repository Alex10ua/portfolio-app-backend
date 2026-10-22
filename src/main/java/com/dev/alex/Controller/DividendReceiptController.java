package com.dev.alex.Controller;

import com.dev.alex.Model.DividendReceipt;
import com.dev.alex.Service.DividendReceiptServiceImpl;
import com.dev.alex.Service.PortfolioAccessService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Per-payment overrides behind the Dividend Calendar's receipt checks and
 * withholding rates. The calendar itself is computed; only what the user changed
 * is stored here.
 */
@RestController
@RequestMapping("/api/v1")
public class DividendReceiptController {

    @Autowired
    private DividendReceiptServiceImpl dividendReceiptService;
    @Autowired
    private PortfolioAccessService portfolioAccessService;

    @GetMapping("/{portfolioId}/dividend-receipts")
    public List<DividendReceipt> getReceipts(@PathVariable String portfolioId, Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        return dividendReceiptService.getReceipts(portfolioId);
    }

    /**
     * Body {@code {ticker, exDate, status, withholdingRate}}; both of the last two
     * are replaced. Both null clears the override and answers 204.
     */
    @PutMapping("/{portfolioId}/dividend-receipts")
    public ResponseEntity<DividendReceipt> saveReceipt(@PathVariable String portfolioId,
                                                       @RequestBody DividendReceipt body,
                                                       Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        DividendReceipt saved = dividendReceiptService.saveReceipt(portfolioId, body);
        return saved == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(saved);
    }
}
