package com.dev.alex;

import com.dev.alex.Config.GlobalExceptionHandler;
import com.dev.alex.Controller.PortfolioPerformanceController;
import com.dev.alex.Model.NonDbModel.PnlSummary;
import com.dev.alex.Service.PortfolioAccessService;
import com.dev.alex.Service.PortfolioPerformanceServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PnlSummaryControllerTests {
    private final PortfolioAccessService access = mock(PortfolioAccessService.class);
    private final PortfolioPerformanceServiceImpl performance = mock(PortfolioPerformanceServiceImpl.class);
    private final UsernamePasswordAuthenticationToken user =
            UsernamePasswordAuthenticationToken.authenticated("owner", "unused", List.of());
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        PortfolioPerformanceController controller = new PortfolioPerformanceController();
        ReflectionTestUtils.setField(controller, "portfolioAccessService", access);
        ReflectionTestUtils.setField(controller, "performanceService", performance);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void verifiesOwnershipBeforeReturningNativeFeeMaps() throws Exception {
        when(performance.getPnlSummary("p")).thenReturn(new PnlSummary(
                Map.of("USD", new BigDecimal("46")), Map.of("USD", new BigDecimal("5")),
                Map.of("USD", new BigDecimal("4"))));

        mvc.perform(get("/api/v1/p/pnl-summary").principal(user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.realizedPnLByCurrency.USD").value(46))
                .andExpect(jsonPath("$.commissionsByCurrency.USD").value(5))
                .andExpect(jsonPath("$.realizedCommissionsByCurrency.USD").value(4));

        var order = inOrder(access, performance);
        order.verify(access).assertOwnership("p", "owner");
        order.verify(performance).getPnlSummary("p");
    }

    @Test
    void rejectsAnotherPortfolioBeforeReadingItsFigures() throws Exception {
        doThrow(new AccessDeniedException("denied")).when(access).assertOwnership("other", "owner");

        mvc.perform(get("/api/v1/other/pnl-summary").principal(user))
                .andExpect(status().isForbidden());

        verifyNoInteractions(performance);
    }
}
