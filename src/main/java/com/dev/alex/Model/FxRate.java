package com.dev.alex.Model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.util.Date;

// exchangeRates document schema (matches what ecb_provider.py writes):
// { _id: currency, currency: str, rateVsEur: float, date: "YYYY-MM-DD", updatedAt: ISODate }
@Document("exchangeRates")
@AllArgsConstructor
@NoArgsConstructor
@Data
public class FxRate {
    @Id
    private String id;           // currency code, e.g. "USD"
    private String currency;     // same as id
    private BigDecimal rateVsEur; // units of currency per 1 EUR (EUR=1.0, USD≈1.10)
    private String date;         // "YYYY-MM-DD" string
    private Date updatedAt;
}
