package com.dev.alex;

import com.dev.alex.Config.GlobalExceptionHandler;
import com.dev.alex.Controller.TransactionController;
import com.dev.alex.Model.Enums.Assets;
import com.dev.alex.Model.Enums.TransactionType;
import com.dev.alex.Model.Transactions;
import com.dev.alex.Repository.TransactionsRepository;
import com.dev.alex.Service.HoldingServiceImpl;
import com.dev.alex.Service.PortfolioAccessService;
import com.dev.alex.Service.PortfolioPerformanceServiceImpl;
import com.dev.alex.Service.TickersServiceImpl;
import com.dev.alex.Service.TransactionCreationService;
import com.dev.alex.Service.WebCalls.FlaskClientService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Run against a disposable MongoDB, never the portfolio database. No provider calls. */
@SpringBootTest(properties = "spring.mongodb.database=transaction_retry_tests")
class TransactionCreationTests {
    @Autowired private TransactionController controller;
    @Autowired private TransactionsRepository transactions;
    @Autowired private TransactionCreationService creation;
    @MockitoBean private HoldingServiceImpl holdings;
    @MockitoBean private TickersServiceImpl tickers;
    @MockitoBean private FlaskClientService flask;
    @MockitoBean private PortfolioPerformanceServiceImpl performance;
    @MockitoBean private PortfolioAccessService access;

    private final String portfolio = "retry-test-" + UUID.randomUUID();
    private final UsernamePasswordAuthenticationToken user =
            UsernamePasswordAuthenticationToken.authenticated("test-user", "unused", List.of());
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
        when(holdings.updateOrCreateHoldingInPortfolioUpdated(anyString(), any())).thenReturn(true);
    }

    @AfterEach
    void cleanUp() {
        transactions.deleteAllByPortfolioId(portfolio);
        transactions.deleteAllByPortfolioId(portfolio + "-other");
    }

    @Test
    void concurrentRetriesInsertOneRowAndRunSideEffectsOnce() throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Callable<ResponseEntity<?>>> attempts = new ArrayList<>();
            for (int i = 0; i < 8; i++) attempts.add(() -> {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return controller.createTransaction(trade(), portfolio, user, "same-attempt");
            });
            var futures = attempts.stream().map(pool::submit).toList();
            start.countDown();
            String id = null;
            for (var future : futures) {
                ResponseEntity<?> response = future.get(30, TimeUnit.SECONDS);
                assertEquals(200, response.getStatusCode().value());
                Transactions saved = (Transactions) ((Map<?, ?>) response.getBody()).get("save");
                if (id == null) id = saved.getTransactionId();
                assertEquals(id, saved.getTransactionId());
            }
        }
        assertEquals(1, transactions.findAllByPortfolioId(portfolio).size());
        verify(holdings, times(1)).updateOrCreateHoldingInPortfolioUpdated(eq(portfolio), any());
        verify(tickers, times(1)).saveIfNotExists("AAPL");
        verify(performance, times(1)).evictRealizedPnLCache(portfolio);
        verifyNoInteractions(flask);
    }

    @Test
    void replayUsesOriginalFingerprintAfterEditsAndDecimalScaleChanges() {
        controller.createTransaction(trade(), portfolio, user, "edited");
        Transactions stored = transactions.findAllByPortfolioId(portfolio).getFirst();
        stored.setPrice(new BigDecimal("999"));
        transactions.save(stored);
        Transactions retry = trade();
        retry.setTicker("aapl");
        retry.setPrice(new BigDecimal("10.000"));
        ResponseEntity<?> response = controller.createTransaction(retry, portfolio, user, "edited");
        assertEquals("true", response.getHeaders().getFirst("Idempotency-Replayed"));
        assertEquals(true, ((Map<?, ?>) response.getBody()).get("holdingSynced"));
        assertEquals(1, transactions.findAllByPortfolioId(portfolio).size());
        verify(holdings, times(1)).updateOrCreateHoldingInPortfolioUpdated(eq(portfolio), any());
    }

    @Test
    void changedPayloadReturns409AndDoesNotInsert() throws Exception {
        controller.createTransaction(trade(), portfolio, user, "conflict");
        mvc.perform(post("/api/v1/{pid}/createTransaction", portfolio).principal(user)
                .header("Idempotency-Key", "conflict").contentType("application/json")
                .content(json("11")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").exists());
        assertEquals(1, transactions.findAllByPortfolioId(portfolio).size());
    }

    @Test
    void keysAreScopedToPortfolioAndOptionalForLegacyClients() {
        controller.createTransaction(trade(), portfolio, user, "shared-key");
        controller.createTransaction(trade(), portfolio + "-other", user, "shared-key");
        controller.createTransaction(trade(), portfolio, user, null);
        controller.createTransaction(trade(), portfolio, user, null);
        assertEquals(3, transactions.findAllByPortfolioId(portfolio).size());
        assertEquals(1, transactions.findAllByPortfolioId(portfolio + "-other").size());
    }

    @Test
    void validationFailureDoesNotConsumeKeyAndMetadataIsPrivate() throws Exception {
        mvc.perform(post("/api/v1/{pid}/createTransaction", portfolio).principal(user)
                .header("Idempotency-Key", "fixable").contentType("application/json")
                .content("{\"transactionType\":\"BUY\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/{pid}/createTransaction", portfolio).principal(user)
                .header("Idempotency-Key", "fixable").contentType("application/json").content(json("10")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.save.transactionId").isString())
                .andExpect(jsonPath("$.save.idempotencyKey").doesNotExist())
                .andExpect(jsonPath("$.save.creationRequestHash").doesNotExist())
                .andExpect(jsonPath("$.save.creationHoldingSynced").doesNotExist());
        for (String invalid : List.of("", "has space", "x".repeat(129))) {
            assertThrows(IllegalArgumentException.class, () -> creation.prepare(trade(), invalid));
        }
        assertEquals(1, transactions.findAllByPortfolioId(portfolio).size());
    }

    @Test
    void ownershipIsCheckedBeforeLookingUpRetry() {
        controller.createTransaction(trade(), portfolio, user, "private");
        doThrow(new AccessDeniedException("denied")).when(access).assertOwnership(portfolio, "test-user");
        assertThrows(AccessDeniedException.class,
                () -> controller.createTransaction(trade(), portfolio, user, "private"));
    }

    @Test
    void holdingFailureStillReplaysSavedTransactionWithoutRepeatingWork() {
        when(holdings.updateOrCreateHoldingInPortfolioUpdated(anyString(), any()))
                .thenThrow(new IllegalStateException("provider unavailable"));
        controller.createTransaction(trade(), portfolio, user, "failed-sync");
        ResponseEntity<?> replay = controller.createTransaction(trade(), portfolio, user, "failed-sync");
        assertEquals(false, ((Map<?, ?>) replay.getBody()).get("holdingSynced"));
        assertEquals(1, transactions.findAllByPortfolioId(portfolio).size());
        verify(holdings, times(1)).updateOrCreateHoldingInPortfolioUpdated(eq(portfolio), any());
    }

    private Transactions trade() {
        Transactions t = new Transactions();
        t.setTicker("AAPL");
        t.setDate(LocalDate.of(2026, 10, 20));
        t.setAssetType(Assets.STOCK);
        t.setTransactionType(TransactionType.BUY);
        t.setQuantity(new BigDecimal("2"));
        t.setPrice(new BigDecimal("10"));
        t.setCurrency("USD");
        return t;
    }

    private String json(String price) {
        return """
                {"ticker":"AAPL","date":"2026-10-20","assetType":"STOCK",
                 "transactionType":"BUY","quantity":2,"price":%s,"currency":"USD"}
                """.formatted(price);
    }
}
