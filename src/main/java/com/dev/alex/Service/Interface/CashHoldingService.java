package com.dev.alex.Service.Interface;

import com.dev.alex.Model.CashHolding;

import java.math.BigDecimal;
import java.util.List;

public interface CashHoldingService {
    List<CashHolding> getCashHoldings(String portfolioId);
    CashHolding upsertCashHolding(String portfolioId, String currency, BigDecimal amount);
    void deleteCashHolding(String portfolioId, String currency);
}
