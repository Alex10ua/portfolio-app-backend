package com.dev.alex.Controller;

import com.dev.alex.Model.CustomAsset;
import com.dev.alex.Service.CustomAssetServiceImpl;
import com.dev.alex.Service.PortfolioAccessService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class CustomAssetController {

    @Autowired
    private CustomAssetServiceImpl customAssetService;
    @Autowired
    private PortfolioAccessService portfolioAccessService;

    @Operation(summary = "Create custom asset definition for a portfolio")
    @PostMapping("/{portfolioId}/custom-assets")
    public ResponseEntity<?> createCustomAsset(@PathVariable String portfolioId,
                                               @RequestBody CustomAsset customAsset,
                                               Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        CustomAsset created = customAssetService.create(portfolioId, customAsset);
        return ResponseEntity.ok(created);
    }

    @Operation(summary = "Get all custom assets for a portfolio")
    @GetMapping("/{portfolioId}/custom-assets")
    public ResponseEntity<List<CustomAsset>> getAllCustomAssets(@PathVariable String portfolioId, Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        return ResponseEntity.ok(customAssetService.findAllByPortfolioId(portfolioId));
    }

    @Operation(summary = "Get a single custom asset by ticker")
    @GetMapping("/{portfolioId}/custom-assets/{ticker}")
    public ResponseEntity<?> getCustomAsset(@PathVariable String portfolioId,
                                            @PathVariable String ticker,
                                            Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        return ResponseEntity.ok(customAssetService.findByPortfolioIdAndTicker(portfolioId, ticker));
    }

    @Operation(summary = "Update custom asset definition")
    @PutMapping("/{portfolioId}/custom-assets/{ticker}")
    public ResponseEntity<?> updateCustomAsset(@PathVariable String portfolioId,
                                               @PathVariable String ticker,
                                               @RequestBody CustomAsset updatedAsset,
                                               Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        return ResponseEntity.ok(customAssetService.update(portfolioId, ticker, updatedAsset));
    }

    @Operation(summary = "Delete custom asset")
    @DeleteMapping("/{portfolioId}/custom-assets/{ticker}")
    public ResponseEntity<Map<String, Boolean>> deleteCustomAsset(@PathVariable String portfolioId,
                                                                   @PathVariable String ticker,
                                                                   Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        customAssetService.delete(portfolioId, ticker);
        return ResponseEntity.ok(Map.of("deleted", true));
    }

    @Operation(summary = "Update current price of a custom asset (appends to priceHistory)")
    @PutMapping("/{portfolioId}/custom-assets/{ticker}/price")
    public ResponseEntity<?> updatePrice(@PathVariable String portfolioId,
                                         @PathVariable String ticker,
                                         @RequestBody Map<String, BigDecimal> body,
                                         Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        BigDecimal newPrice = body.get("price");
        if (newPrice == null) {
            return ResponseEntity.badRequest().body("Field 'price' is required");
        }
        return ResponseEntity.ok(customAssetService.updatePrice(portfolioId, ticker, newPrice));
    }
}
