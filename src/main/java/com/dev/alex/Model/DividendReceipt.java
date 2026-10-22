package com.dev.alex.Model;

import com.dev.alex.Model.Enums.DividendReceiptStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * What the user changed about one dividend payment on the Dividend Calendar:
 * its receipt status and/or the withholding-tax rate applied to it. A payment is
 * identified by ticker + ex-date. Either field null means "the default" (status
 * by pay date, rate by listing country); a document with both null is deleted.
 *
 * <p>Kept when the position closes — these describe payments that happened.
 */
@Document(collection = "dividendReceipts")
@CompoundIndex(def = "{'portfolioId': 1, 'ticker': 1, 'exDate': 1}", unique = true)
@AllArgsConstructor
@NoArgsConstructor
@Data
public class DividendReceipt {
    @Id
    private String id;
    private String portfolioId;
    private String ticker;
    private LocalDate exDate;
    private DividendReceiptStatus status;
    /** Percent withheld at source, 0–100. */
    private BigDecimal withholdingRate;
    private LocalDate updatedAt;
}
