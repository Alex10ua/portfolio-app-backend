package com.dev.alex.Controller;

import com.dev.alex.Model.Transactions;
import com.dev.alex.Repository.HoldingsRepository;
import com.dev.alex.Repository.TransacrionsRepository;
import com.dev.alex.Service.HoldingServiceImpl;
import com.dev.alex.Service.TransactionServiceImpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@CrossOrigin(origins = "http://localhost:3000")//fix Access-Control-Allow-Origin
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
    @PostMapping("/{portfolioId}/createTransaction")
    public ResponseEntity<?> createTransaction(@RequestBody Transactions transaction, @PathVariable String portfolioId){
        transaction.setTransactionId(UUID.randomUUID().toString());
        transaction.setPortfolioId(portfolioId);
        transaction.setTotalAmount(transaction.getPrice().multiply(transaction.getQuantity()));
        Transactions transactionStatus = transacrionsRepository.save(transaction);
        //need check if ticker exists in portfolio first
        holdingService.updateOrCreateHoldingInPortfolio(portfolioId, transaction);
        Map<String, Object> response = new HashMap<>();
        response.put("save", transactionStatus);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{portfolioId}/transactions")
    public List<Transactions> getAllTransactionByPortfolioId(@PathVariable String portfolioId){
        return transactionService.findAllByPortfolioId(portfolioId);
    }
    @PutMapping("/{portfolioId}/transactions/{transactionId}/update")
    public ResponseEntity<Transactions> updateTransaction(@RequestBody Transactions transaction, @PathVariable String transactionId){
        Transactions updatedTransaction;
        updatedTransaction = transaction;
        return ResponseEntity.ok(updatedTransaction);
    }
    @DeleteMapping("/{portfolioId}/transactions/{transactionId}/delete")
    public ResponseEntity<Map<String, Boolean>> deleteTransaction(@PathVariable String transactionId){
        Transactions transaction = transacrionsRepository.findById(transactionId).orElseThrow(RuntimeException::new);
        transacrionsRepository.deleteById(transactionId);
        Map<String, Boolean> response = new HashMap<>();
        response.put("deleted", Boolean.TRUE);
        return ResponseEntity.ok(response);
    }


}
