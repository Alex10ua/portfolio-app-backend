package com.dev.alex;

import com.dev.alex.Config.GlobalExceptionHandler;
import com.dev.alex.Controller.Ai.AiRealEstateController;
import com.dev.alex.Model.NonDbModel.Ai.AiEnvelope;
import com.dev.alex.Service.Ai.AiEnvelopeService;
import com.dev.alex.Service.Ai.AiRealEstateAssembler;
import com.dev.alex.Service.PortfolioAccessService;
import com.dev.alex.Service.RealEstateServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AiRealEstateControllerTests {
    private MockMvc mvc;
    private PortfolioAccessService access;
    private AiRealEstateAssembler assembler;
    private AiEnvelopeService envelope;
    private final TestingAuthenticationToken authentication = new TestingAuthenticationToken("synthetic-user", "unused");

    @BeforeEach
    void setup() {
        access = mock(PortfolioAccessService.class);
        assembler = mock(AiRealEstateAssembler.class);
        envelope = mock(AiEnvelopeService.class);
        AiRealEstateController controller = new AiRealEstateController();
        ReflectionTestUtils.setField(controller, "portfolioAccessService", access);
        ReflectionTestUtils.setField(controller, "assembler", assembler);
        ReflectionTestUtils.setField(controller, "envelopeService", envelope);
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void everyReadIncludingCalculatorsRejectsForeignPortfoliosBeforeReadingData() throws Exception {
        doThrow(new AccessDeniedException("no")).when(access).assertOwnership("foreign", "synthetic-user");
        List<String> paths = List.of("properties", "properties/home", "loans", "properties/home/loans/loan",
                "properties/home/loans/loan/schedule", "wealth", "wealth/history",
                "properties/home/loans/loan/extra-payment-preview?amount=5000",
                "properties/home/loans/loan/rate-preview?effectiveFrom=2026-11-08&annualNominalRatePercent=5");
        for (String path : paths) {
            mvc.perform(get("/api/v1/ai/foreign/" + path).principal(authentication)).andExpect(status().isForbidden());
        }
        verify(access, times(paths.size())).assertOwnership("foreign", "synthetic-user");
        verifyNoInteractions(assembler, envelope);
    }

    @Test
    void propertyListChecksOwnershipBeforeAssemblerAndReturnsAnEnvelope() throws Exception {
        when(assembler.properties("portfolio", "ALL")).thenReturn(List.of());
        when(assembler.baseCurrency("synthetic-user", "portfolio")).thenReturn("EUR");
        when(envelope.wrap(any(), eq("EUR"), any(String[].class))).thenAnswer(invocation ->
                new AiEnvelope<>(invocation.getArgument(0), Map.of("EUR", BigDecimal.ONE), "EUR",
                        AiEnvelopeService.CONVERSION_FORMULA, List.of(AiRealEstateAssembler.NOTE_REAL_ESTATE)));
        mvc.perform(get("/api/v1/ai/portfolio/properties").principal(authentication)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray()).andExpect(jsonPath("$.baseCurrency").value("EUR"));
        var order = inOrder(access, assembler);
        order.verify(access).assertOwnership("portfolio", "synthetic-user");
        order.verify(assembler).properties("portfolio", "ALL");
    }

    @Test
    void calculatorsAreGetOnlyAndBindDecimalAmountsWithoutPersistingAnything() throws Exception {
        mvc.perform(get("/api/v1/ai/portfolio/properties/home/loans/loan/extra-payment-preview")
                        .param("date", "2026-11-09").param("amount", "1234.56").principal(authentication))
                .andExpect(status().isOk());
        verify(assembler).extraPaymentPreview("portfolio", "home", "loan", LocalDate.of(2026, 11, 9), new BigDecimal("1234.56"));
        mvc.perform(get("/api/v1/ai/portfolio/properties/home/loans/loan/rate-preview")
                        .param("effectiveFrom", "2026-12-01").param("fixedUntil", "2030-12-01")
                        .param("annualNominalRatePercent", "4.25").param("monthlyPayment", "500.12")
                        .param("extraPrincipalPaid", "5000.01").principal(authentication)).andExpect(status().isOk());
        verify(assembler).ratePreview("portfolio", "home", "loan", LocalDate.of(2026, 12, 1),
                LocalDate.of(2030, 12, 1), new BigDecimal("4.25"), new BigDecimal("500.12"), new BigDecimal("5000.01"));
        mvc.perform(post("/api/v1/ai/portfolio/properties/home/loans/loan/extra-payment-preview")
                        .principal(authentication)).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void nestedDomainFailureRetains404AndBadDatesRetain400() throws Exception {
        when(assembler.loan("portfolio", "home", "foreign-loan"))
                .thenThrow(new RealEstateServiceImpl.RealEstateException(404, "Mortgage not found"));
        mvc.perform(get("/api/v1/ai/portfolio/properties/home/loans/foreign-loan").principal(authentication))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("Mortgage not found"));
        mvc.perform(get("/api/v1/ai/portfolio/wealth/history").param("from", "bad-date").principal(authentication))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/ai/portfolio/properties/home/loans/loan/rate-preview")
                        .param("effectiveFrom", "2026-12-01").principal(authentication)).andExpect(status().isBadRequest());
    }
}
