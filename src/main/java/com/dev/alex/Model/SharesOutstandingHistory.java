package com.dev.alex.Model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDate;
import java.util.List;

/**
 * Share-count time series, one doc per ticker (_id = ticker), written by Flask:
 * live providers append a point whenever the value changes
 * (record_shares_history), and the SEC EDGAR backfill merges whole filing
 * histories in (record_shares_history_bulk). Sparse by design — entries exist
 * only for dates the count actually changed, so consumers must carry the last
 * value forward rather than assume a regular interval.
 * For crypto the "shares" figure is circulating supply.
 */
@Document("sharesOutstandingHistory")
@AllArgsConstructor
@NoArgsConstructor
@Data
public class SharesOutstandingHistory {
    @Id
    private String ticker;
    private List<SharesHistoryEntry> history;
    private LocalDate lastUpdated;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class SharesHistoryEntry {
        private LocalDate date;
        private Long shares;
    }
}
