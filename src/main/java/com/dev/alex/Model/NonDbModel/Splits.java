package com.dev.alex.Model.NonDbModel;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Date;
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Splits {

    private LocalDate splitDate;
    private BigDecimal ratioSplit;
}
