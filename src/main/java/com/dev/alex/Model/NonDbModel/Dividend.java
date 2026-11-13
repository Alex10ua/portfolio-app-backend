package com.dev.alex.Model.NonDbModel;

import com.dev.alex.Config.DividendAmountConverter;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.convert.ValueConverter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Date;
@Data
@AllArgsConstructor
@NoArgsConstructor
public class Dividend {

    private LocalDate dividendDate;
    @ValueConverter(DividendAmountConverter.class)
    private BigDecimal dividendAmount;
    private LocalDate  exDividendDate;
}
