package com.dev.alex.Model.NonDbModel;

import com.dev.alex.Model.SharesOutstandingHistory.SharesHistoryEntry;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Everything the Historical page shows for one ticker in a single response:
 * dividend progression, split events and the share-count series. Lists are
 * sorted ascending by date and never null (empty when the ticker has none).
 * currency is marketData.currency — the currency dividends are quoted in
 * (may be "GBp" pence, unlike the transaction currency).
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class TickerHistoricalData {
    private String ticker;
    private String name;
    private String currency;
    private List<Dividend> dividends;
    private List<Splits> splits;
    private List<SharesHistoryEntry> sharesHistory;
}
