package com.dev.alex.Service.Ai;

import com.dev.alex.Controller.Ai.AiPlanningController;
import com.dev.alex.Config.GlobalExceptionHandler;
import com.dev.alex.Model.CustomAsset;
import com.dev.alex.Model.DividendReceipt;
import com.dev.alex.Model.Enums.DividendReceiptStatus;
import com.dev.alex.Model.NonDbModel.DividendsCalendarData;
import com.dev.alex.Model.NonDbModel.HoldingsCompleteData;
import com.dev.alex.Model.NonDbModel.PriceHistoryEntry;
import com.dev.alex.Service.DividendCalendarServiceImpl;
import com.dev.alex.Service.DividendReceiptServiceImpl;
import com.dev.alex.Service.PortfolioAccessService;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AiPlanningTests {
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 8);

    @Test
    void automaticReceiptIsExplicitlyAnInferenceAndWithholdingIsUnknown() {
        var row = dividend("2026-10-01", "2026-10-15", false);
        var payment = AiPlanningAssembler.payment(row, null, TODAY);
        assertEquals("RECEIVED", payment.status());
        assertEquals("DATE_RULE", payment.receiptStatusSource());
        assertFalse(payment.explicitlyConfirmedReceived());
        assertNull(payment.receiptOverrideStatus());
        assertNull(payment.withholdingRatePercent());
        assertNull(payment.estimatedWithheldAmount());
        assertNull(payment.estimatedNetAmount());
        assertEquals("NOT_RECORDED", payment.withholdingSource());
        amount("20", payment.grossAmount());
    }

    @Test
    void projectedPaymentNeverAutoConfirmsEvenAfterItsDate() {
        var payment = AiPlanningAssembler.payment(dividend("2026-01-01", "2026-01-15", true), null, TODAY);
        assertEquals("AWAITING", payment.naturalStatus());
        assertEquals("AWAITING", payment.status());
        assertTrue(payment.scheduled());
        assertFalse(payment.explicitlyConfirmedReceived());
    }

    @Test
    void userOverrideAndEstimatedWithholdingStaySeparateFromNaturalStatus() {
        var receipt = receipt("2026-12-01", DividendReceiptStatus.RECEIVED);
        receipt.setWithholdingRate(new BigDecimal("15"));
        var payment = AiPlanningAssembler.payment(dividend("2026-12-01", "2026-12-15", false), receipt, TODAY);
        assertEquals("UPCOMING", payment.naturalStatus());
        assertEquals("RECEIVED", payment.status());
        assertEquals("USER_OVERRIDE", payment.receiptStatusSource());
        assertTrue(payment.explicitlyConfirmedReceived());
        amount("3", payment.estimatedWithheldAmount());
        amount("17", payment.estimatedNetAmount());
    }

    @Test
    void unknownCurrencyAndDividendNeverBecomeAZeroOrAssumedCurrency() {
        var row = dividend("2026-10-01", null, false);
        row.setCurrency(null);
        row.setDividendAmount(null);
        var payment = AiPlanningAssembler.payment(row, null, TODAY);
        assertNull(payment.currency());
        assertNull(payment.grossAmount());
        assertEquals("RECEIVED", payment.status()); // ex-date + thirty-day rule
        assertFalse(payment.explicitlyConfirmedReceived());
    }

    @Test
    void receiptFollowsShiftedExDateButDoesNotTakeAnotherDeclaredPaymentsOverride() {
        var row = dividend("2026-10-10", "2026-10-25", false);
        var nearby = receipt("2026-10-01", DividendReceiptStatus.MISSING);
        assertSame(nearby, AiPlanningAssembler.matchReceipt(row, List.of(nearby), Set.of("A|2026-10-10"), 2026));
        assertNull(AiPlanningAssembler.matchReceipt(row, List.of(nearby), Set.of("A|2026-10-01"), 2026));
        var tooFar = receipt("2026-09-01", DividendReceiptStatus.RECEIVED);
        assertNull(AiPlanningAssembler.matchReceipt(row, List.of(tooFar), Set.of(), 2026));
    }

    @Test
    void statusFilterKeepsFullCalendarCounts() {
        var calendar = mock(DividendCalendarServiceImpl.class);
        var receipts = mock(DividendReceiptServiceImpl.class);
        when(calendar.getDividendCalendarByPortfolioId("p", 2026)).thenReturn(Map.of("OCTOBER", List.of(
                dividend("2026-10-01", "2026-10-15", false), dividend("2026-12-01", "2026-12-15", true))));
        when(receipts.getReceipts("p")).thenReturn(List.of());
        var assembler = new AiPlanningAssembler();
        ReflectionTestUtils.setField(assembler, "calendarService", calendar);
        ReflectionTestUtils.setField(assembler, "receiptService", receipts);
        var result = assembler.dividendPayments("p", 2026, "received", TODAY);
        assertEquals(1, result.payments().size());
        assertEquals(1L, result.countsByStatus().get("RECEIVED"));
        assertEquals(1L, result.countsByStatus().get("UPCOMING"));
        assertThrows(IllegalArgumentException.class, () -> assembler.dividendPayments("p", 2026, "invented", TODAY));
    }

    @Test
    void oneShiftedReceiptCannotConfirmTwoProjectedEvents() {
        var calendar = mock(DividendCalendarServiceImpl.class);
        var receipts = mock(DividendReceiptServiceImpl.class);
        when(calendar.getDividendCalendarByPortfolioId("p", 2026)).thenReturn(Map.of("OCTOBER", List.of(
                dividend("2026-10-09", "2026-10-15", true), dividend("2026-10-11", "2026-10-16", true))));
        when(receipts.getReceipts("p")).thenReturn(List.of(receipt("2026-10-10", DividendReceiptStatus.RECEIVED)));
        var assembler = new AiPlanningAssembler();
        ReflectionTestUtils.setField(assembler, "calendarService", calendar);
        ReflectionTestUtils.setField(assembler, "receiptService", receipts);
        var result = assembler.dividendPayments("p", 2026, "ALL", TODAY);
        assertEquals(1, result.payments().stream().filter(p -> p.explicitlyConfirmedReceived()).count());
        assertEquals(1, result.payments().stream().filter(p -> p.status().equals("AWAITING")).count());
    }

    @Test
    void invalidYearStatusAndPeriodStayClientErrorsWithTheRealExceptionAdvice() throws Exception {
        AiPlanningController controller = new AiPlanningController();
        ReflectionTestUtils.setField(controller, "assembler", new AiPlanningAssembler());
        ReflectionTestUtils.setField(controller, "portfolioAccessService", mock(PortfolioAccessService.class));
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
        var user = UsernamePasswordAuthenticationToken.authenticated("owner", "unused", List.of());
        mvc.perform(get("/api/v1/ai/p/dividend-payments").param("year", "0").principal(user)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/ai/p/dividend-payments").param("status", "invented").principal(user)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/ai/p/self-funding").param("period", "invented").principal(user)).andExpect(status().isBadRequest());
    }

    @Test
    void selfFundingUsesTheQuoteCurrencyAndLeavesUnknownCadenceNull() {
        var holding = holding("10000", "400", "10");
        holding.setCurrency("EUR");
        holding.setQuoteCurrency("GBp");
        var row = AiPlanningAssembler.selfFundingRow(holding, 1, null, List.of("income"));
        assertEquals("GBp", row.currency());
        amount("25", row.neededShares());
        amount("15", row.gapShares());
        amount("150000", row.gapCost());
        amount("40", row.coveragePercent());
        amount("4", row.yieldPercent());
        assertNull(row.dividendPerPayment());
        assertTrue(row.estimatedReinvestmentYears().signum() > 0);
        assertEquals(List.of("income"), row.tags());
    }

    @Test
    void selfFundingCoverageIsUncappedAndPeriodIsIndependentOfCadence() {
        var row = AiPlanningAssembler.selfFundingRow(holding("10", "12", "52"), 12, 4, List.of());
        amount("10", row.neededShares());
        amount("520", row.coveragePercent());
        amount("1", row.dividendPerPeriod());
        amount("3", row.dividendPerPayment());
        amount("0", row.gapShares());
        amount("0", row.estimatedReinvestmentYears());
        assertEquals("SELF_FUNDING", row.tier());
        assertNull(AiPlanningAssembler.selfFundingRow(holding("10", null, "52"), 1, null, List.of()));
    }

    @Test
    void boundedCustomHistoryRetainsLatestDatesWithoutChangingStoredDefinition() {
        CustomAsset asset = new CustomAsset();
        asset.setTicker("GOLD");
        asset.setCurrency(null);
        asset.setPriceNow(null);
        asset.setPriceHistory(List.of(point("2026-03-01"), point("2026-01-01"), point("2026-02-01")));
        var result = AiPlanningAssembler.customAsset(asset, 2);
        assertEquals(3, result.priceHistoryTotalCount());
        assertTrue(result.historyTruncated());
        assertEquals(List.of(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 3, 1)),
                result.priceHistory().stream().map(PriceHistoryEntry::getDate).toList());
        assertEquals(3, asset.getPriceHistory().size());
        assertEquals(LocalDate.of(2026, 3, 1), asset.getPriceHistory().getFirst().getDate());
        assertNull(result.currency());
        assertNull(result.priceNow());
        assertTrue(AiPlanningAssembler.customAsset(asset, 0).priceHistory().isEmpty());
    }

    @Test
    void everyPortfolioPlanningReadChecksOwnershipBeforeReadingData() {
        AiPlanningController controller = new AiPlanningController();
        AiPlanningAssembler assembler = mock(AiPlanningAssembler.class);
        PortfolioAccessService access = mock(PortfolioAccessService.class);
        Authentication authentication = mock(Authentication.class);
        when(authentication.getName()).thenReturn("other");
        doThrow(new SecurityException("Denied")).when(access).assertOwnership("p", "other");
        ReflectionTestUtils.setField(controller, "assembler", assembler);
        ReflectionTestUtils.setField(controller, "portfolioAccessService", access);
        assertThrows(SecurityException.class, () -> controller.dividendPayments("p", 2026, "ALL", authentication));
        assertThrows(SecurityException.class, () -> controller.note("p", "A", authentication));
        assertThrows(SecurityException.class, () -> controller.customAssets("p", 0, authentication));
        assertThrows(SecurityException.class, () -> controller.customAsset("p", "A", 10, authentication));
        assertThrows(SecurityException.class, () -> controller.selfFunding("p", "YEARLY", authentication));
        verifyNoInteractions(assembler);
    }

    private static DividendsCalendarData dividend(String exDate, String payDate, boolean scheduled) {
        var row = new DividendsCalendarData("A", new BigDecimal("2"), new BigDecimal("10"), scheduled);
        row.setExDate(LocalDate.parse(exDate));
        row.setPayDate(payDate == null ? null : LocalDate.parse(payDate));
        row.setCurrency("USD");
        row.setCountry("United States");
        return row;
    }
    private static DividendReceipt receipt(String exDate, DividendReceiptStatus status) {
        DividendReceipt receipt = new DividendReceipt();
        receipt.setTicker("A");
        receipt.setExDate(LocalDate.parse(exDate));
        receipt.setStatus(status);
        return receipt;
    }
    private static HoldingsCompleteData holding(String price, String dividend, String shares) {
        var holding = new HoldingsCompleteData();
        holding.setTicker("A");
        holding.setCurrentShareValue(new BigDecimal(price));
        holding.setDividend(dividend == null ? null : new BigDecimal(dividend));
        holding.setShareAmount(new BigDecimal(shares));
        return holding;
    }
    private static PriceHistoryEntry point(String date) { return new PriceHistoryEntry(LocalDate.parse(date), BigDecimal.ONE); }
    private static void amount(String expected, BigDecimal actual) { assertNotNull(actual); assertEquals(0, new BigDecimal(expected).compareTo(actual)); }
}
