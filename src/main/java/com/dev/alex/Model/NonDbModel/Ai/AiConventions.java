package com.dev.alex.Model.NonDbModel.Ai;

import java.util.List;
import java.util.Map;

/**
 * The data contract for every /api/v1/ai/** response, in one place so a caller
 * can load it once instead of re-reading the same caveats on each tool.
 */
public record AiConventions(
        List<String> currencyRules,
        List<String> valueRules,
        /** Assets enum value -&gt; what it means here. */
        Map<String, String> assetTypes,
        /** TransactionType value -&gt; how it affects cash and share count. */
        Map<String, String> transactionTypes,
        List<String> knownLimitations,
        /** Endpoint path -&gt; what it answers. */
        Map<String, String> endpoints) {
}
