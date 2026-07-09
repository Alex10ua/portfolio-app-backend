package com.dev.alex.Controller;

import com.dev.alex.Model.CashHolding;
import com.dev.alex.Service.CashHoldingServiceImpl;
import com.dev.alex.Service.PortfolioAccessService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class CashHoldingController {

    @Autowired
    private CashHoldingServiceImpl cashHoldingService;
    @Autowired
    private PortfolioAccessService portfolioAccessService;

    @GetMapping("/{portfolioId}/cash")
    public List<CashHolding> getCashHoldings(@PathVariable String portfolioId, Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        return cashHoldingService.getCashHoldings(portfolioId);
    }

    @PutMapping("/{portfolioId}/cash")
    public ResponseEntity<CashHolding> upsertCashHolding(@PathVariable String portfolioId,
                                                         @RequestBody Map<String, Object> body,
                                                         Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        String currency = body.get("currency") == null ? null : String.valueOf(body.get("currency"));
        BigDecimal amount = new BigDecimal(String.valueOf(body.get("amount")));
        return ResponseEntity.ok(cashHoldingService.upsertCashHolding(portfolioId, currency, amount));
    }

    @DeleteMapping("/{portfolioId}/cash/{currency}")
    public ResponseEntity<Void> deleteCashHolding(@PathVariable String portfolioId,
                                                  @PathVariable String currency,
                                                  Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        cashHoldingService.deleteCashHolding(portfolioId, currency);
        return ResponseEntity.noContent().build();
    }
}
