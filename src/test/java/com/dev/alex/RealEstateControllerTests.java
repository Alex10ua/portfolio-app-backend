package com.dev.alex;

import com.dev.alex.Config.GlobalExceptionHandler;
import com.dev.alex.Controller.RealEstateController;
import com.dev.alex.Service.PortfolioAccessService;
import com.dev.alex.Service.RealEstateServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RealEstateControllerTests {
    private MockMvc mvc;
    private PortfolioAccessService access;
    private RealEstateServiceImpl realEstate;
    private final TestingAuthenticationToken authentication = new TestingAuthenticationToken("synthetic-user", "unused");

    @BeforeEach
    void setup() {
        access = mock(PortfolioAccessService.class);
        realEstate = mock(RealEstateServiceImpl.class);
        RealEstateController controller = new RealEstateController();
        ReflectionTestUtils.setField(controller, "portfolioAccessService", access);
        ReflectionTestUtils.setField(controller, "realEstateService", realEstate);
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void listChecksOwnershipBeforeReadingProperties() throws Exception {
        when(realEstate.viewsFor(eq("portfolio-a"), any(LocalDate.class))).thenReturn(List.of());
        mvc.perform(get("/api/v1/portfolio-a/properties").principal(authentication))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        var order = inOrder(access, realEstate);
        order.verify(access).assertOwnership("portfolio-a", "synthetic-user");
        order.verify(realEstate).viewsFor(eq("portfolio-a"), any(LocalDate.class));
    }

    @Test
    void anotherUsersPortfolioNeverReachesDomainRead() throws Exception {
        doThrow(new AccessDeniedException("no")).when(access).assertOwnership("portfolio-b", "synthetic-user");
        mvc.perform(get("/api/v1/portfolio-b/properties").principal(authentication)).andExpect(status().isForbidden());
        verifyNoInteractions(realEstate);
    }

    @Test
    void nestedLookupFailureKeepsIts404InsteadOfBecoming500() throws Exception {
        when(realEstate.getMortgage("portfolio-a", "property-a", "foreign-loan"))
                .thenThrow(new RealEstateServiceImpl.RealEstateException(404, "Mortgage not found"));
        mvc.perform(get("/api/v1/portfolio-a/properties/property-a/mortgages/foreign-loan").principal(authentication))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("Mortgage not found"));
    }

    @Test
    void staleVersionConflictIsAnActionable409() throws Exception {
        when(realEstate.updateMortgage(eq("portfolio-a"), eq("property-a"), eq("loan-a"), any(), eq("change-mode")))
                .thenThrow(new RealEstateServiceImpl.RealEstateException(409, "Mortgage changed elsewhere; reload before saving again"));
        mvc.perform(patch("/api/v1/portfolio-a/properties/property-a/mortgages/loan-a")
                        .principal(authentication).header("Idempotency-Key", "change-mode")
                        .contentType("application/json").content("{\"version\":0,\"balanceTrackingMode\":\"STATEMENT_ONLY\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("Mortgage changed elsewhere; reload before saving again"));
        verify(access).assertOwnership("portfolio-a", "synthetic-user");
    }

    @Test
    void deletePropertyChecksOwnershipAndAnswersNoContent() throws Exception {
        mvc.perform(delete("/api/v1/portfolio-a/properties/property-a").param("version", "3").principal(authentication))
                .andExpect(status().isNoContent());
        var order = inOrder(access, realEstate);
        order.verify(access).assertOwnership("portfolio-a", "synthetic-user");
        order.verify(realEstate).deleteProperty("portfolio-a", "property-a", 3L);
    }

    @Test
    void deleteMortgagePassesTheChosenFinancing() throws Exception {
        mvc.perform(delete("/api/v1/portfolio-a/properties/property-a/mortgages/loan-a")
                        .param("version", "2").param("financingAfter", "MORTGAGE_FREE").principal(authentication))
                .andExpect(status().isOk());
        verify(realEstate).deleteMortgage("portfolio-a", "property-a", "loan-a", 2L, "MORTGAGE_FREE");
    }

    @Test
    void extraPaymentPreviewChecksOwnershipFirst() throws Exception {
        mvc.perform(post("/api/v1/portfolio-a/properties/property-a/mortgages/loan-a/extra-payment-preview").principal(authentication)
                        .contentType("application/json").content("{\"date\":\"2026-10-27\",\"amount\":\"5000\"}"))
                .andExpect(status().isOk());
        var order = inOrder(access, realEstate);
        order.verify(access).assertOwnership("portfolio-a", "synthetic-user");
        order.verify(realEstate).extraPaymentPreview(eq("portfolio-a"), eq("property-a"), eq("loan-a"), any());
    }

    @Test
    void anotherUsersPortfolioNeverReachesDelete() throws Exception {
        doThrow(new AccessDeniedException("no")).when(access).assertOwnership("portfolio-b", "synthetic-user");
        mvc.perform(delete("/api/v1/portfolio-b/properties/property-a").param("version", "0").principal(authentication))
                .andExpect(status().isForbidden());
        verifyNoInteractions(realEstate);
    }
}
