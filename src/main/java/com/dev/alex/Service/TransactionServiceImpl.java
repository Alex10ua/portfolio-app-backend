package com.dev.alex.Service;

import com.dev.alex.Model.Transactions;
import com.dev.alex.Repository.TransactionsRepository;
import com.dev.alex.Service.Interface.TransactionService;
import org.springframework.beans.factory.annotation.Autowired;
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
    public void createTransactions(List<Transactions> transactions) {

    }

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
    public void updateTransaction(Transactions transaction, String transactionId, String portfolioId) {
        // Find the existing transaction by its ID
        Transactions existingTransaction = transactionsRepository.findById(transactionId)
                .orElseThrow(() -> new RuntimeException("Transaction not found with id: " + transactionId));

        // Verify that the transaction belongs to the correct portfolio
        if (!existingTransaction.getPortfolioId().equals(portfolioId)) {
            throw new RuntimeException(
                    "Transaction with id " + transactionId + " does not belong to portfolio " + portfolioId);
        }

        // Update the fields of the existing transaction with the new values
        // Note: This assumes your Transactions model has these setters.
        // The ID and portfolioId should typically not be changed.
        existingTransaction.setTicker(transaction.getTicker());
        existingTransaction.setPrice(transaction.getPrice());
        existingTransaction.setQuantity(transaction.getQuantity());
        existingTransaction.setDate(transaction.getDate());
        existingTransaction.setTransactionType(transaction.getTransactionType());

        // Save the updated transaction back to the database
        transactionsRepository.save(existingTransaction);
    }

    @Override
    public void deleteTransaction(String transactionId, String portfolioId) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'deleteTransaction'");
    }
}
