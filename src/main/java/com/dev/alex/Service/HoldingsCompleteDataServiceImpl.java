package com.dev.alex.Service;

import com.dev.alex.Model.HoldingsCompleteData;
import com.dev.alex.Repository.HoldingsRepository;
import com.dev.alex.Service.Interface.HoldingsCompleteDataService;
import com.dev.alex.Service.Interface.HoldingsService;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

public class HoldingsCompleteDataServiceImpl implements HoldingsCompleteDataService {
    @Autowired
    private HoldingsRepository holdingsRepository;
    @Override
    public List<HoldingsCompleteData> getDataForAllHoldings() {

        return List.of(null);
    }
}
