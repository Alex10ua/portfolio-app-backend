package com.dev.alex.Service.Interface;

import com.dev.alex.Model.DividendReceipt;

import java.util.List;

public interface DividendReceiptService {
    List<DividendReceipt> getReceipts(String portfolioId);

    /**
     * Stores the status and rate of {@code receipt} for its ticker + ex-date,
     * replacing both. Returns null when both are null — the override is removed
     * and the payment falls back to its defaults.
     */
    DividendReceipt saveReceipt(String portfolioId, DividendReceipt receipt);
}
