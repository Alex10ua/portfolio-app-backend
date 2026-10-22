package com.dev.alex.Service;

import com.dev.alex.Model.DividendReceipt;
import com.dev.alex.Repository.DividendReceiptRepository;
import com.dev.alex.Service.Interface.DividendReceiptService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
public class DividendReceiptServiceImpl implements DividendReceiptService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    @Autowired
    private DividendReceiptRepository dividendReceiptRepository;

    @Override
    public List<DividendReceipt> getReceipts(String portfolioId) {
        return dividendReceiptRepository.findAllByPortfolioId(portfolioId);
    }

    @Override
    public DividendReceipt saveReceipt(String portfolioId, DividendReceipt receipt) {
        if (receipt == null || receipt.getTicker() == null || receipt.getTicker().isBlank()) {
            throw new IllegalArgumentException("Ticker is required");
        }
        if (receipt.getExDate() == null) {
            throw new IllegalArgumentException("Ex-dividend date is required");
        }
        BigDecimal rate = receipt.getWithholdingRate();
        if (rate != null && (rate.signum() < 0 || rate.compareTo(HUNDRED) > 0)) {
            throw new IllegalArgumentException("Withholding rate must be between 0 and 100, got: " + rate);
        }
        // Tickers are stored as the calendar reports them; trimming only.
        String ticker = receipt.getTicker().trim();
        Optional<DividendReceipt> existing = dividendReceiptRepository
                .findByPortfolioIdAndTickerAndExDate(portfolioId, ticker, receipt.getExDate());

        if (receipt.getStatus() == null && rate == null) {
            existing.ifPresent(dividendReceiptRepository::delete);
            return null;
        }
        DividendReceipt saved = existing.orElseGet(() -> {
            DividendReceipt created = new DividendReceipt();
            created.setPortfolioId(portfolioId);
            created.setTicker(ticker);
            created.setExDate(receipt.getExDate());
            return created;
        });
        saved.setStatus(receipt.getStatus());
        saved.setWithholdingRate(rate);
        saved.setUpdatedAt(LocalDate.now());
        return dividendReceiptRepository.save(saved);
    }
}
