package com.dev.alex.Service;

import com.dev.alex.Model.Enums.TransactionType;
import com.dev.alex.Model.Transactions;
import com.dev.alex.Repository.TransactionsRepository;
import com.dev.alex.Service.Interface.TransactionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
@Transactional
public class TransactionServiceImpl implements TransactionService {
    @Autowired
    private TransactionsRepository transactionsRepository;

    @Override
    public List<Transactions> findAllByPortfolioId(String portfolioId) {
        return transactionsRepository.findAllByPortfolioId(portfolioId);
    }

    @Override
    public List<Transactions> findAllByPortfolioIdAndTicker(String portfolioId, String ticker) {
        return transactionsRepository.findAllByPortfolioIdAndTicker(portfolioId, ticker);
    }

    @Override
    public List<Transactions> findAllByPortfolioIdAndYear(String portfolioId, int year) {
        LocalDate startDate = LocalDate.of(year, 1, 1);
        LocalDate endDate = LocalDate.of(year, 12, 31);
        return transactionsRepository.findAllByPortfolioIdAndDateBetween(portfolioId, startDate, endDate);
    }

    @Override
    public List<Transactions> findBuySellByPortfolioIdAndYear(String portfolioId, int year) {
        LocalDate startDate = LocalDate.of(year, 1, 1);
        LocalDate endDate = LocalDate.of(year, 12, 31);
        return transactionsRepository.findAllByPortfolioIdAndDateBetweenAndTransactionTypeIn(
                portfolioId, startDate, endDate, List.of(TransactionType.BUY, TransactionType.SELL));
    }

    @Override
    public Transactions updateTransaction(Transactions transaction, String transactionId, String portfolioId) {
        // Find the existing transaction by its ID
        Transactions existingTransaction = transactionsRepository.findById(transactionId)
                .orElseThrow(() -> new RuntimeException("Transaction not found with id: " + transactionId));

        // Verify that the transaction belongs to the correct portfolio
        if (!existingTransaction.getPortfolioId().equals(portfolioId)) {
            throw new AccessDeniedException("Transaction does not belong to this portfolio");
        }

        // Copy every editable field — commission, currency and assetType used to be dropped here,
        // so an edit to them silently kept the stored value.
        //
        // A field absent from the request body deserializes to null, indistinguishable from an
        // explicit null, and the edit form only sends the seven fields it renders. So a null means
        // "not sent", never "clear this": the SELL dialog would otherwise wipe the name/amount/
        // priceNow a dividend or custom-asset transaction carries. Clearing a value is done by
        // sending 0 or an empty string, which is what the form does.
        if (transaction.getTicker() != null) {
            existingTransaction.setTicker(transaction.getTicker().toUpperCase());
        }
        if (transaction.getName() != null)            existingTransaction.setName(transaction.getName());
        if (transaction.getAssetType() != null)       existingTransaction.setAssetType(transaction.getAssetType());
        if (transaction.getTransactionType() != null) existingTransaction.setTransactionType(transaction.getTransactionType());
        if (transaction.getQuantity() != null)        existingTransaction.setQuantity(transaction.getQuantity());
        if (transaction.getPrice() != null)           existingTransaction.setPrice(transaction.getPrice());
        if (transaction.getAmount() != null)          existingTransaction.setAmount(transaction.getAmount());
        if (transaction.getPriceNow() != null)        existingTransaction.setPriceNow(transaction.getPriceNow());
        if (transaction.getCurrency() != null)        existingTransaction.setCurrency(transaction.getCurrency());
        if (transaction.getCommission() != null)      existingTransaction.setCommission(transaction.getCommission());
        if (transaction.getDate() != null)            existingTransaction.setDate(transaction.getDate());

        // Same derivation the create path uses — a quantity or price edit must not leave a
        // totalAmount computed from the old values behind.
        if (existingTransaction.getPrice() != null && existingTransaction.getQuantity() != null) {
            existingTransaction.setTotalAmount(
                    existingTransaction.getPrice().multiply(existingTransaction.getQuantity()));
        } else if (transaction.getTotalAmount() != null) {
            existingTransaction.setTotalAmount(transaction.getTotalAmount());
        }

        return transactionsRepository.save(existingTransaction);
    }
}
