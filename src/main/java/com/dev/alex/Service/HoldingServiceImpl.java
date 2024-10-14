package com.dev.alex.Service;

import com.dev.alex.Model.Holdings;
import com.dev.alex.Repository.HoldingsRepository;
import com.dev.alex.Service.Interface.HoldingsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class HoldingServiceImpl implements HoldingsService {

    @Autowired
    private HoldingsRepository holdingsRepository;

    @Override
    public List<Holdings> getAllHoldingsByPortfolioId(String portfolioId) {
       return holdingsRepository.findAllByPortfolioId(portfolioId);
    }

    @Override
    public void updateHoldingsByHoldingId(String holdingId) {

    }

    @Override
    public void updateHoldingByPortfolioIdAndTickerSymbol(String portfolioId, String tickerSymbol, Holdings holding) {

    }

    @Override
    public Holdings findHoldingByPortfolioIdAndTicker(String portfolioId, String tickerSymbol) {
        return holdingsRepository.findByPortfolioIdAndTicker(portfolioId, tickerSymbol);
    }


}
