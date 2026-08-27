package com.dev.alex.Model.NonDbModel.Ai;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * One user tag with the positions carrying it and their value per currency.
 * Tags are user-scoped labels ([a-z0-9-], no '#'); a ticker may carry several.
 */
public record AiTagGroup(
        String tag,
        List<TagTicker> tickers,
        Map<String, BigDecimal> totalsByCurrency) {

    public record TagTicker(String ticker, BigDecimal marketValue, String currency) {
    }
}
