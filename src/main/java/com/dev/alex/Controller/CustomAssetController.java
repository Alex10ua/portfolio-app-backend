package com.dev.alex.Controller;

import com.dev.alex.Model.CustomAsset;
import com.dev.alex.Service.CustomAssetServiceImpl;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@CrossOrigin(origins = "http://localhost:3001")
public class CustomAssetController {

    @Autowired
    private CustomAssetServiceImpl customAssetService;

    @Operation(summary = "Create custom asset definition for a portfolio")
    @PostMapping("/{portfolioId}/custom-assets")
    public ResponseEntity<?> createCustomAsset(@PathVariable String portfolioId,
                                               @RequestBody CustomAsset customAsset) {
        try {
            CustomAsset created = customAssetService.create(portfolioId, customAsset);
            return ResponseEntity.ok(created);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    @Operation(summary = "Get all custom assets for a portfolio")
    @GetMapping("/{portfolioId}/custom-assets")
    public ResponseEntity<List<CustomAsset>> getAllCustomAssets(@PathVariable String portfolioId) {
        return ResponseEntity.ok(customAssetService.findAllByPortfolioId(portfolioId));
    }

    @Operation(summary = "Get a single custom asset by ticker")
    @GetMapping("/{portfolioId}/custom-assets/{ticker}")
    public ResponseEntity<?> getCustomAsset(@PathVariable String portfolioId,
                                            @PathVariable String ticker) {
        try {
            return ResponseEntity.ok(customAssetService.findByPortfolioIdAndTicker(portfolioId, ticker));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @Operation(summary = "Update custom asset definition")
    @PutMapping("/{portfolioId}/custom-assets/{ticker}")
    public ResponseEntity<?> updateCustomAsset(@PathVariable String portfolioId,
                                               @PathVariable String ticker,
                                               @RequestBody CustomAsset updatedAsset) {
        try {
            return ResponseEntity.ok(customAssetService.update(portfolioId, ticker, updatedAsset));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @Operation(summary = "Delete custom asset")
    @DeleteMapping("/{portfolioId}/custom-assets/{ticker}")
    public ResponseEntity<Map<String, Boolean>> deleteCustomAsset(@PathVariable String portfolioId,
                                                                   @PathVariable String ticker) {
        customAssetService.delete(portfolioId, ticker);
        return ResponseEntity.ok(Map.of("deleted", true));
    }

    @Operation(summary = "Update current price of a custom asset (appends to priceHistory)")
    @PutMapping("/{portfolioId}/custom-assets/{ticker}/price")
    public ResponseEntity<?> updatePrice(@PathVariable String portfolioId,
                                         @PathVariable String ticker,
                                         @RequestBody Map<String, BigDecimal> body) {
        BigDecimal newPrice = body.get("price");
        if (newPrice == null) {
            return ResponseEntity.badRequest().body("Field 'price' is required");
        }
        try {
            return ResponseEntity.ok(customAssetService.updatePrice(portfolioId, ticker, newPrice));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
