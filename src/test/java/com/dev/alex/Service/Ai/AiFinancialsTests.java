package com.dev.alex.Service.Ai;

import com.dev.alex.Model.YahooFinancials;
import com.dev.alex.Repository.YahooFinancialsRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiFinancialsTests {
    @Test
    void financialsKeepReportingCurrencyAndNullsAndBoundPeriodsWithoutMutatingStoredData() {
        YahooFinancials raw = new YahooFinancials();
        raw.setTicker("FOREIGN.L");
        raw.setCurrency("EUR");
        raw.setUpdatedAt(new Date(0));
        var entries = List.of(entry("2024-12-31", "10"), entry("2026-12-31", "30"), entry("2025-12-31", "20"));
        raw.setAnnual(new YahooFinancials.Statements(Map.of("TotalRevenue", entries), null, null));
        var repository = mock(YahooFinancialsRepository.class);
        when(repository.findById("FOREIGN.L")).thenReturn(Optional.of(raw));
        AiTickerAssembler assembler = new AiTickerAssembler();
        ReflectionTestUtils.setField(assembler, "financialsRepository", repository);

        var result = assembler.financials("FOREIGN.L", 2);
        assertEquals("EUR", result.getCurrency());
        assertNull(result.getTrailing());
        assertNull(result.getAnnual().getBalanceSheet());
        assertEquals(List.of("2026-12-31", "2025-12-31"),
                result.getAnnual().getIncome().get("TotalRevenue").stream().map(YahooFinancials.StatementEntry::getDate).toList());
        assertEquals(3, raw.getAnnual().getIncome().get("TotalRevenue").size());
        assertEquals("2024-12-31", raw.getAnnual().getIncome().get("TotalRevenue").getFirst().getDate());
        verify(repository, never()).save(any());
    }

    @Test
    void checkedWithoutStatementsIsMissingRatherThanAnEmptyFinancialSnapshot() {
        YahooFinancials raw = new YahooFinancials();
        raw.setTicker("NO-DATA");
        raw.setCheckedAt(new Date(0));
        var repository = mock(YahooFinancialsRepository.class);
        when(repository.findById("NO-DATA")).thenReturn(Optional.of(raw));
        AiTickerAssembler assembler = new AiTickerAssembler();
        ReflectionTestUtils.setField(assembler, "financialsRepository", repository);

        assertNull(assembler.financials("NO-DATA", 5));
        verify(repository, never()).save(any());
    }

    private static YahooFinancials.StatementEntry entry(String date, String value) {
        return new YahooFinancials.StatementEntry(date, new BigDecimal(value));
    }
}
