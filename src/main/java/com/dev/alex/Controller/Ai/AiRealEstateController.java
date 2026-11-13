package com.dev.alex.Controller.Ai;

import com.dev.alex.Model.NonDbModel.Ai.AiEnvelope;
import com.dev.alex.Model.NonDbModel.Ai.AiRealEstateData.*;
import com.dev.alex.Model.NonDbModel.WealthData;
import com.dev.alex.Service.Ai.AiEnvelopeService;
import com.dev.alex.Service.Ai.AiRealEstateAssembler;
import com.dev.alex.Service.PortfolioAccessService;
import com.dev.alex.Service.RealEstateServiceImpl;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Every route, including unsaved calculators, remains a guarded read. */
@RestController
@RequestMapping("/api/v1/ai/{portfolioId}")
public class AiRealEstateController {
    @Autowired private AiRealEstateAssembler assembler;
    @Autowired private AiEnvelopeService envelopeService;
    @Autowired private PortfolioAccessService portfolioAccessService;

    @Operation(summary = "Properties with every associated loan", description = "Compact records; status is ALL, ACTIVE or ARCHIVED.")
    @GetMapping("/properties")
    public AiEnvelope<List<PropertySummary>> properties(@PathVariable String portfolioId,
            @RequestParam(defaultValue = "ALL") String status, Authentication authentication) {
        String username = authentication.getName();
        portfolioAccessService.assertOwnership(portfolioId, username);
        return wrap(assembler.properties(portfolioId, status), username, portfolioId);
    }

    @GetMapping("/properties/{propertyId}")
    public AiEnvelope<PropertyDetail> property(@PathVariable String portfolioId, @PathVariable String propertyId,
                                               Authentication authentication) {
        String username = authentication.getName();
        portfolioAccessService.assertOwnership(portfolioId, username);
        return wrap(assembler.property(portfolioId, propertyId), username, portfolioId);
    }

    @Operation(summary = "All property loans in a portfolio", description = "Includes closed loans by default. Optional propertyId and ALL, ACTIVE or CLOSED status.")
    @GetMapping("/loans")
    public AiEnvelope<List<LoanSummary>> loans(@PathVariable String portfolioId,
            @RequestParam(required = false) String propertyId, @RequestParam(defaultValue = "ALL") String status,
            Authentication authentication) {
        String username = authentication.getName();
        portfolioAccessService.assertOwnership(portfolioId, username);
        return wrap(assembler.loans(portfolioId, propertyId, status), username, portfolioId);
    }

    @GetMapping("/properties/{propertyId}/loans/{loanId}")
    public AiEnvelope<LoanDetail> loan(@PathVariable String portfolioId, @PathVariable String propertyId,
                                       @PathVariable String loanId, Authentication authentication) {
        String username = authentication.getName();
        portfolioAccessService.assertOwnership(portfolioId, username);
        return wrap(assembler.loan(portfolioId, propertyId, loanId), username, portfolioId);
    }

    @GetMapping("/properties/{propertyId}/loans/{loanId}/schedule")
    public AiEnvelope<SchedulePage> schedule(@PathVariable String portfolioId, @PathVariable String propertyId,
            @PathVariable String loanId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "200") int limit,
            Authentication authentication) {
        String username = authentication.getName();
        portfolioAccessService.assertOwnership(portfolioId, username);
        return wrap(assembler.schedule(portfolioId, propertyId, loanId, from, to, offset, limit), username, portfolioId);
    }

    @GetMapping("/wealth")
    public AiEnvelope<WealthSnapshot> wealth(@PathVariable String portfolioId, Authentication authentication) {
        String username = authentication.getName();
        portfolioAccessService.assertOwnership(portfolioId, username);
        return wrap(assembler.wealth(portfolioId, username), username, portfolioId, AiRealEstateAssembler.NOTE_WEALTH);
    }

    @GetMapping("/wealth/history")
    public AiEnvelope<List<WealthData.HistoryPoint>> wealthHistory(@PathVariable String portfolioId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer maxPoints, Authentication authentication) {
        String username = authentication.getName();
        portfolioAccessService.assertOwnership(portfolioId, username);
        return wrap(assembler.wealthHistory(portfolioId, from, to, maxPoints), username, portfolioId,
                AiRealEstateAssembler.NOTE_HISTORY);
    }

    @GetMapping("/properties/{propertyId}/loans/{loanId}/extra-payment-preview")
    public AiEnvelope<ExtraPaymentPreview> extraPaymentPreview(@PathVariable String portfolioId,
            @PathVariable String propertyId, @PathVariable String loanId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam BigDecimal amount, Authentication authentication) {
        String username = authentication.getName();
        portfolioAccessService.assertOwnership(portfolioId, username);
        return wrap(assembler.extraPaymentPreview(portfolioId, propertyId, loanId, date, amount), username, portfolioId,
                AiRealEstateAssembler.NOTE_PREVIEW);
    }

    @GetMapping("/properties/{propertyId}/loans/{loanId}/rate-preview")
    public AiEnvelope<RatePreview> ratePreview(@PathVariable String portfolioId, @PathVariable String propertyId,
            @PathVariable String loanId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate effectiveFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fixedUntil,
            @RequestParam BigDecimal annualNominalRatePercent,
            @RequestParam(required = false) BigDecimal monthlyPayment,
            @RequestParam(required = false) BigDecimal extraPrincipalPaid, Authentication authentication) {
        String username = authentication.getName();
        portfolioAccessService.assertOwnership(portfolioId, username);
        return wrap(assembler.ratePreview(portfolioId, propertyId, loanId, effectiveFrom, fixedUntil,
                annualNominalRatePercent, monthlyPayment, extraPrincipalPaid), username, portfolioId,
                AiRealEstateAssembler.NOTE_PREVIEW);
    }

    @ExceptionHandler(RealEstateServiceImpl.RealEstateException.class)
    public ResponseEntity<Map<String, String>> realEstateError(RealEstateServiceImpl.RealEstateException exception) {
        return ResponseEntity.status(exception.getStatus()).body(Map.of("error", exception.getMessage()));
    }

    private <T> AiEnvelope<T> wrap(T data, String username, String portfolioId, String... notes) {
        String[] allNotes = new String[notes.length + 1];
        allNotes[0] = AiRealEstateAssembler.NOTE_REAL_ESTATE;
        System.arraycopy(notes, 0, allNotes, 1, notes.length);
        return envelopeService.wrap(data, assembler.baseCurrency(username, portfolioId), allNotes);
    }
}
