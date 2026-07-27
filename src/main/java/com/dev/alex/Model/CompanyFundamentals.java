package com.dev.alex.Model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * SEC EDGAR XBRL fundamentals, one doc per ticker (_id = ticker), written by
 * Flask's /update/fundamentals. Keys are the curated concept set (assets,
 * revenue, netIncome, epsDiluted, ...) — see sec_edgar_provider.py
 * FUNDAMENTAL_CONCEPTS. A concept the company never reported is simply absent
 * from the map, not a zero entry.
 */
@Document(collection = "companyFundamentals")
@AllArgsConstructor
@NoArgsConstructor
@Data
public class CompanyFundamentals {
    @Id
    private String ticker;
    private Map<String, List<FundamentalEntry>> concepts;
    private Date updatedAt;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class FundamentalEntry {
        private String date;   // 'YYYY-MM-DD', the XBRL fact's "end" (as-of) date
        private BigDecimal value;
        private String form;   // e.g. "10-K", "10-Q"
        private String filed;  // filing date, used only for merge tie-breaking upstream
    }
}
