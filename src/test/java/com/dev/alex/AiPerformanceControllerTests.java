package com.dev.alex;

import com.dev.alex.Controller.Ai.AiPerformanceController;
import com.dev.alex.Model.NonDbModel.PerformanceData;
import com.dev.alex.Service.Ai.AiEnvelopeService;
import com.dev.alex.Service.FxRateServiceImpl;
import com.dev.alex.Service.PortfolioAccessService;
import com.dev.alex.Service.PortfolioPerformanceServiceImpl;
import com.dev.alex.Service.UserProfileServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiPerformanceControllerTests {
    private final PortfolioAccessService access = mock(PortfolioAccessService.class);
    private final PortfolioPerformanceServiceImpl performance = mock(PortfolioPerformanceServiceImpl.class);
    private final UsernamePasswordAuthenticationToken user =
            UsernamePasswordAuthenticationToken.authenticated("owner", "unused", List.of());
    private AiPerformanceController controller;

    @BeforeEach
    void setUp() {
        FxRateServiceImpl fx = mock(FxRateServiceImpl.class);
        when(fx.getAllRatesAsMap()).thenReturn(Map.of("USD", new BigDecimal("1.1")));
        AiEnvelopeService envelopes = new AiEnvelopeService();
        ReflectionTestUtils.setField(envelopes, "fxRateService", fx);
        ReflectionTestUtils.setField(envelopes, "userProfileService", mock(UserProfileServiceImpl.class));
        controller = new AiPerformanceController();
        ReflectionTestUtils.setField(controller, "portfolioAccessService", access);
        ReflectionTestUtils.setField(controller, "performanceService", performance);
        ReflectionTestUtils.setField(controller, "envelopeService", envelopes);
    }

    @Test
    void preservesNativeCashFlowsAndSuppressesMixedCurrencyReturns() {
        PerformanceData raw = performance("EUR", "USD");
        when(performance.getPerformance("p", "ALL")).thenReturn(raw);

        var envelope = controller.performance("p", "ALL", null, user);
        var result = envelope.data();

        assertEquals(raw.getCashFlows(), result.cashFlows());
        assertEquals(new BigDecimal("-1000"), result.cashFlows().getFirst().amount());
        assertEquals(LocalDate.now(), result.valuationDate());
        assertEquals("MULTI_CURRENCY_REQUIRES_CONVERSION", result.returnCalculationBasis());
        assertNull(result.returnCurrency());
        assertNull(result.xirrPercent());
        assertNull(result.totalReturnPercent());
        assertNull(result.unrealizedPnLPercent());
        assertEquals(raw.getCurrentValueByCurrency(), result.currentValueByCurrency());
        assertTrue(envelope.notes().contains(AiEnvelopeService.NOTE_PERFORMANCE_RETURNS));
    }

    @Test
    void keepsSingleCurrencyNativeReturns() {
        PerformanceData raw = performance("USD", "USD");
        when(performance.getPerformance("p", "ALL")).thenReturn(raw);

        var result = controller.performance("p", "ALL", null, user).data();

        assertEquals("SINGLE_CURRENCY_NATIVE", result.returnCalculationBasis());
        assertEquals("USD", result.returnCurrency());
        assertEquals(new BigDecimal("12"), result.xirrPercent());
        assertEquals(new BigDecimal("10"), result.totalReturnPercent());
    }

    @Test
    void treatsPenceAndPoundsAsDifferentNativeUnits() {
        when(performance.getPerformance("p", "ALL")).thenReturn(performance("GBP", "GBp"));

        var result = controller.performance("p", "ALL", null, user).data();

        assertEquals("MULTI_CURRENCY_REQUIRES_CONVERSION", result.returnCalculationBasis());
        assertNull(result.xirrPercent());
    }

    @Test
    void missingPerformanceDoesNotInventReturnsOrCashFlows() {
        var result = controller.performance("p", "ALL", null, user).data();

        assertEquals("NO_CURRENCY_DATA", result.returnCalculationBasis());
        assertTrue(result.cashFlows().isEmpty());
        assertTrue(result.timeSeries().isEmpty());
        assertNull(result.xirrPercent());
    }

    @Test
    void checksOwnershipBeforeReadingPerformance() {
        doThrow(new AccessDeniedException("denied")).when(access).assertOwnership("other", "owner");

        assertThrows(AccessDeniedException.class,
                () -> controller.performance("other", "ALL", null, user));

        verifyNoInteractions(performance);
    }

    private static PerformanceData performance(String bookCurrency, String quoteCurrency) {
        PerformanceData raw = new PerformanceData();
        raw.setTotalInvestedByCurrency(Map.of(bookCurrency, new BigDecimal("1000")));
        raw.setOpenCostBasisByCurrency(Map.of(bookCurrency, new BigDecimal("1000")));
        raw.setCurrentValueByCurrency(Map.of(quoteCurrency, new BigDecimal("1100")));
        raw.setCashFlows(List.of(new PerformanceData.CashFlow(
                LocalDate.of(2025, 1, 1), new BigDecimal("-1000"), bookCurrency)));
        raw.setXirr(new BigDecimal("12"));
        raw.setTotalReturnPct(new BigDecimal("10"));
        raw.setUnrealizedPnLPct(new BigDecimal("10"));
        return raw;
    }
}
