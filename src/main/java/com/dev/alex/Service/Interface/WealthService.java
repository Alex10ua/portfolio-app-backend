package com.dev.alex.Service.Interface;

import com.dev.alex.Model.NonDbModel.WealthData;

import java.time.LocalDate;
import java.util.List;

public interface WealthService {
    WealthData.Summary current(String portfolioId);
    List<WealthData.HistoryPoint> history(String portfolioId, LocalDate from, LocalDate to);
    List<WealthData.PropertyHistoryPoint> propertyHistory(String portfolioId, String propertyId);
}
