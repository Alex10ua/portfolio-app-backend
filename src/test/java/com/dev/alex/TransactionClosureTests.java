package com.dev.alex;

import com.dev.alex.Model.Enums.Assets;
import com.dev.alex.Model.Enums.TransactionType;
import com.dev.alex.Model.Transactions;
import com.dev.alex.Repository.TransactionsRepository;
import com.dev.alex.Service.TransactionCreationService;
import com.dev.alex.Service.TransactionServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Nullable closing metadata must not rewrite real fills or invalidate existing retry keys. */
class TransactionClosureTests {
    @Test
    void closureEditPreservesTheActualFillAndCanBeReversedWithoutChangingOtherFields() {
        TransactionsRepository repository = mock(TransactionsRepository.class);
        TransactionServiceImpl service = new TransactionServiceImpl();
        ReflectionTestUtils.setField(service, "transactionsRepository", repository);
        Transactions stored = sale();
        stored.setPortfolioId("p");
        stored.setTransactionId("sale");
        when(repository.findById("sale")).thenReturn(Optional.of(stored));
        when(repository.save(any(Transactions.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Transactions close = new Transactions();
        close.setClosesPosition(true);
        Transactions updated = service.updateTransaction(close, "sale", "p");

        assertEquals(Boolean.TRUE, updated.getClosesPosition());
        amount("1.01", updated.getQuantity());
        amount("183.75", updated.getPrice());
        amount("185.5875", updated.getTotalAmount());
        amount("0.36", updated.getCommission());

        Transactions priceOnly = new Transactions();
        priceOnly.setPrice(new BigDecimal("184"));
        assertEquals(Boolean.TRUE, service.updateTransaction(priceOnly, "sale", "p").getClosesPosition());

        Transactions partial = new Transactions();
        partial.setClosesPosition(false);
        assertEquals(Boolean.FALSE, service.updateTransaction(partial, "sale", "p").getClosesPosition());
        amount("1.01", stored.getQuantity());
    }

    @Test
    void omittedOrFalseClosureFlagKeepsLegacyRetryFingerprintsCompatible() {
        TransactionCreationService creation = new TransactionCreationService();
        Transactions legacy = sale();
        creation.prepare(legacy, "legacy-key");

        // This hash was produced by the existing fingerprint format before closing metadata.
        assertEquals("05aeae1c68f973d945af443848991422bf99f1065ec4a950cdce6747f4f5b671",
                legacy.getCreationRequestHash());

        Transactions ordinarySale = sale();
        ordinarySale.setClosesPosition(false);
        creation.prepare(ordinarySale, "legacy-key");
        assertEquals(legacy.getCreationRequestHash(), ordinarySale.getCreationRequestHash());

        Transactions confirmedClose = sale();
        confirmedClose.setClosesPosition(true);
        creation.prepare(confirmedClose, "legacy-key");
        assertNotEquals(legacy.getCreationRequestHash(), confirmedClose.getCreationRequestHash());
    }

    @Test
    void changingAClosingSaleToABuyClearsTheSaleOnlyFlag() {
        TransactionsRepository repository = mock(TransactionsRepository.class);
        TransactionServiceImpl service = new TransactionServiceImpl();
        ReflectionTestUtils.setField(service, "transactionsRepository", repository);
        Transactions stored = sale();
        stored.setPortfolioId("p");
        stored.setClosesPosition(true);
        when(repository.findById("sale")).thenReturn(Optional.of(stored));
        when(repository.save(any(Transactions.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Transactions buy = new Transactions();
        buy.setTransactionType(TransactionType.BUY);
        Transactions updated = service.updateTransaction(buy, "sale", "p");

        assertEquals(TransactionType.BUY, updated.getTransactionType());
        assertNotEquals(Boolean.TRUE, updated.getClosesPosition());
    }

    private Transactions sale() {
        Transactions transaction = new Transactions();
        transaction.setTicker("TEST");
        transaction.setAssetType(Assets.STOCK);
        transaction.setTransactionType(TransactionType.SELL);
        transaction.setDate(LocalDate.of(2026, 5, 18));
        transaction.setQuantity(new BigDecimal("1.01"));
        transaction.setPrice(new BigDecimal("183.75"));
        transaction.setCommission(new BigDecimal("0.36"));
        transaction.setCurrency("USD");
        return transaction;
    }

    private void amount(String expected, BigDecimal actual) {
        assertNotNull(actual);
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
