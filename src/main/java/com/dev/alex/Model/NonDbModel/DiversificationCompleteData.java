package com.dev.alex.Model.NonDbModel;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DiversificationCompleteData {

    Map<String, BigDecimal> amountByCountry;
    Map<String, BigDecimal> amountBySector;
    Map<String, BigDecimal> amountByIndustry;
    Map<String, BigDecimal> amountByStock;
}
