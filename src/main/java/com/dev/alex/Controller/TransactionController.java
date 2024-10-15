package com.dev.alex.Controller;

import com.dev.alex.Model.Holdings;
import com.dev.alex.Model.Transactions;
import com.dev.alex.Repository.HoldingsRepository;
import com.dev.alex.Repository.TransacrionsRepository;
import com.dev.alex.Service.HoldingServiceImpl;
import com.dev.alex.Service.TransactionServiceImpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.crossstore.ChangeSetPersister;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/transaction")
public class TransactionController {
    @Autowired
    private TransactionServiceImpl transactionService;
    @Autowired
    private TransacrionsRepository transacrionsRepository;
    @Autowired
    private HoldingServiceImpl holdingService;
    @Autowired
    private HoldingsRepository holdingsRepository;

    @Operation(summary = "Create Transaction", description = "Create new transaction")
    @ApiResponse(responseCode = "200", description = "Transaction created successfully")
    @PostMapping("/{userId}/{portfolioId}/")
    public Transactions createTransaction(@RequestBody Transactions transaction, @PathVariable String portfolioId){
        transaction.setTransactionId(UUID.randomUUID().toString());
        transaction.setPortfolioId(portfolioId);
        //need check if ticker exists in portfolio first
        holdingService.updateOrCreateHoldingInPortfolio(portfolioId, transaction);

        return transacrionsRepository.save(transaction);
    }

    @GetMapping("/{userId}/{portfolioId}/transactions")
    public List<Transactions> getAllTransactionByPortfolioIdAndUserId(@PathVariable String portfolioId, @PathVariable String userId){
        return transactionService.findAllTransactionByPortfolioIdAndUserId(portfolioId, userId);
    }
    @PutMapping("/{userId}/{portfolioId}/transactions/{transactionId}/update")
    public ResponseEntity<Transactions> updateTransaction(@RequestBody Transactions transaction, @PathVariable String transactionId){
        Transactions updatedTransaction;
        updatedTransaction = transaction;
        return ResponseEntity.ok(updatedTransaction);
    }
    @DeleteMapping("/{userId}/{portfolioId}/transactions/{transactionId}/delete")
    public ResponseEntity<Map<String, Boolean>> deleteTransaction(@PathVariable String transactionId){
        Transactions transaction = transacrionsRepository.findById(transactionId).orElseThrow(RuntimeException::new);
        transacrionsRepository.deleteById(transactionId);
        Map<String, Boolean> response = new HashMap<>();
        response.put("deleted", Boolean.TRUE);
        return ResponseEntity.ok(response);
    }


}
