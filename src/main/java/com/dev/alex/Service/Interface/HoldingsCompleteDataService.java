package com.dev.alex.Service.Interface;

import com.dev.alex.Model.NonDbModel.HoldingsCompleteData;

import java.util.List;

public interface HoldingsCompleteDataService {

    List<HoldingsCompleteData> getAllHoldingsByPortfolioId(String portfolioId);
}
