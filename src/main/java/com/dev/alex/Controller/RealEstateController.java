package com.dev.alex.Controller;

import com.dev.alex.Model.Mortgage;
import com.dev.alex.Model.NonDbModel.RealEstateCommands.*;
import com.dev.alex.Service.MortgageCalculationService;
import com.dev.alex.Service.PortfolioAccessService;
import com.dev.alex.Service.RealEstateServiceImpl;
import com.dev.alex.Service.RealEstateServiceImpl.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/{portfolioId}/properties")
public class RealEstateController {
    @Autowired private PortfolioAccessService portfolioAccessService;
    @Autowired private RealEstateServiceImpl realEstateService;

    @GetMapping
    public List<PropertyView> properties(@PathVariable String portfolioId, Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        return realEstateService.viewsFor(portfolioId, LocalDate.now());
    }

    @GetMapping("/{propertyId}")
    public PropertyView property(@PathVariable String portfolioId, @PathVariable String propertyId, Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        return realEstateService.getProperty(portfolioId, propertyId);
    }

    @PostMapping
    public PropertyView create(@PathVariable String portfolioId, @RequestBody PropertyCommand command,
            @RequestHeader("Idempotency-Key") String key, Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        return realEstateService.createProperty(portfolioId, command, key);
    }

    @PatchMapping("/{propertyId}")
    public PropertyView update(@PathVariable String portfolioId, @PathVariable String propertyId,
            @RequestBody PropertyCommand command, @RequestHeader("Idempotency-Key") String key, Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        return realEstateService.updateProperty(portfolioId, propertyId, command, key);
    }

    @DeleteMapping("/{propertyId}")
    public ResponseEntity<Void> delete(@PathVariable String portfolioId, @PathVariable String propertyId,
            @RequestParam(required = false) Long version, Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        realEstateService.deleteProperty(portfolioId, propertyId, version);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{propertyId}/valuations")
    public PropertyView valuation(@PathVariable String portfolioId, @PathVariable String propertyId,
            @RequestBody ValuationCommand command, @RequestHeader("Idempotency-Key") String key, Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        return realEstateService.valuation(portfolioId, propertyId, null, command, key);
    }

    @PatchMapping("/{propertyId}/valuations/{valuationId}")
    public PropertyView correctValuation(@PathVariable String portfolioId, @PathVariable String propertyId,
            @PathVariable String valuationId, @RequestBody ValuationCommand command,
            @RequestHeader("Idempotency-Key") String key, Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        return realEstateService.valuation(portfolioId, propertyId, valuationId, command, key);
    }

    @PostMapping("/{propertyId}/archive")
    public PropertyView archive(@PathVariable String portfolioId, @PathVariable String propertyId,
            @RequestBody DateCommand command, @RequestHeader("Idempotency-Key") String key, Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        return realEstateService.archive(portfolioId, propertyId, command, key);
    }

    @GetMapping("/{propertyId}/mortgages")
    public List<Mortgage> mortgages(@PathVariable String portfolioId, @PathVariable String propertyId, Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        return realEstateService.getMortgages(portfolioId, propertyId);
    }

    @PostMapping("/{propertyId}/mortgages")
    public PropertyView createMortgage(@PathVariable String portfolioId, @PathVariable String propertyId,
            @RequestBody MortgageCommand command, @RequestHeader("Idempotency-Key") String key, Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        return realEstateService.createMortgage(portfolioId, propertyId, command, key);
    }

    @GetMapping("/{propertyId}/mortgages/{mortgageId}")
    public MortgageView mortgage(@PathVariable String portfolioId, @PathVariable String propertyId,
            @PathVariable String mortgageId, Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        return realEstateService.getMortgage(portfolioId, propertyId, mortgageId);
    }

    @PatchMapping("/{propertyId}/mortgages/{mortgageId}")
    public PropertyView updateMortgage(@PathVariable String portfolioId, @PathVariable String propertyId,
            @PathVariable String mortgageId, @RequestBody MortgageCommand command,
            @RequestHeader("Idempotency-Key") String key, Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        return realEstateService.updateMortgage(portfolioId, propertyId, mortgageId, command, key);
    }

    @DeleteMapping("/{propertyId}/mortgages/{mortgageId}")
    public PropertyView deleteMortgage(@PathVariable String portfolioId, @PathVariable String propertyId,
            @PathVariable String mortgageId, @RequestParam(required = false) Long version,
            @RequestParam(required = false) String financingAfter, Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        return realEstateService.deleteMortgage(portfolioId, propertyId, mortgageId, version, financingAfter);
    }

    @PostMapping("/{propertyId}/mortgages/{mortgageId}/payments")
    public PropertyView payment(@PathVariable String portfolioId, @PathVariable String propertyId,
            @PathVariable String mortgageId, @RequestBody PaymentCommand command,
            @RequestHeader("Idempotency-Key") String key, Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        return realEstateService.payment(portfolioId, propertyId, mortgageId, null, command, key);
    }

    @PatchMapping("/{propertyId}/mortgages/{mortgageId}/payments/{paymentId}")
    public PropertyView correctPayment(@PathVariable String portfolioId, @PathVariable String propertyId,
            @PathVariable String mortgageId, @PathVariable String paymentId, @RequestBody PaymentCommand command,
            @RequestHeader("Idempotency-Key") String key, Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        return realEstateService.payment(portfolioId, propertyId, mortgageId, paymentId, command, key);
    }

    @PostMapping("/{propertyId}/mortgages/{mortgageId}/balance-checkpoints")
    public PropertyView checkpoint(@PathVariable String portfolioId, @PathVariable String propertyId,
            @PathVariable String mortgageId, @RequestBody BalanceCommand command,
            @RequestHeader("Idempotency-Key") String key, Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        return realEstateService.checkpoint(portfolioId, propertyId, mortgageId, null, command, key);
    }

    @PatchMapping("/{propertyId}/mortgages/{mortgageId}/balance-checkpoints/{checkpointId}")
    public PropertyView correctCheckpoint(@PathVariable String portfolioId, @PathVariable String propertyId,
            @PathVariable String mortgageId, @PathVariable String checkpointId, @RequestBody BalanceCommand command,
            @RequestHeader("Idempotency-Key") String key, Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        return realEstateService.checkpoint(portfolioId, propertyId, mortgageId, checkpointId, command, key);
    }

    @PostMapping("/{propertyId}/mortgages/{mortgageId}/rate-periods")
    public PropertyView rate(@PathVariable String portfolioId, @PathVariable String propertyId,
            @PathVariable String mortgageId, @RequestBody RateCommand command,
            @RequestHeader("Idempotency-Key") String key, Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        return realEstateService.rate(portfolioId, propertyId, mortgageId, null, command, key);
    }

    @PatchMapping("/{propertyId}/mortgages/{mortgageId}/rate-periods/{rateId}")
    public PropertyView correctRate(@PathVariable String portfolioId, @PathVariable String propertyId,
            @PathVariable String mortgageId, @PathVariable String rateId, @RequestBody RateCommand command,
            @RequestHeader("Idempotency-Key") String key, Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        return realEstateService.rate(portfolioId, propertyId, mortgageId, rateId, command, key);
    }

    @GetMapping("/{propertyId}/mortgages/{mortgageId}/schedule")
    public List<MortgageCalculationService.Installment> schedule(@PathVariable String portfolioId, @PathVariable String propertyId,
            @PathVariable String mortgageId, @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to, Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        return realEstateService.schedule(portfolioId, propertyId, mortgageId, from, to);
    }

    @PostMapping("/{propertyId}/mortgages/{mortgageId}/preview")
    public Preview preview(@PathVariable String portfolioId, @PathVariable String propertyId,
            @PathVariable String mortgageId, @RequestBody PreviewCommand command, Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        return realEstateService.preview(portfolioId, propertyId, mortgageId, command);
    }

    @PostMapping("/{propertyId}/mortgages/{mortgageId}/extra-payment-preview")
    public ExtraPaymentPreview extraPaymentPreview(@PathVariable String portfolioId, @PathVariable String propertyId,
            @PathVariable String mortgageId, @RequestBody ExtraPaymentCommand command, Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        return realEstateService.extraPaymentPreview(portfolioId, propertyId, mortgageId, command);
    }

    @PostMapping("/{propertyId}/mortgages/{mortgageId}/close")
    public PropertyView close(@PathVariable String portfolioId, @PathVariable String propertyId,
            @PathVariable String mortgageId, @RequestBody DateCommand command,
            @RequestHeader("Idempotency-Key") String key, Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        return realEstateService.close(portfolioId, propertyId, mortgageId, command, key);
    }

    @ExceptionHandler(RealEstateException.class)
    public ResponseEntity<Map<String, String>> realEstateError(RealEstateException exception) {
        return ResponseEntity.status(exception.getStatus()).body(Map.of("error", exception.getMessage()));
    }
}
