package com.dev.alex.Model.NonDbModel.Ai;

import java.math.BigDecimal;
import java.util.List;

/**
 * Target weight of every holding against what it actually weighs today — the
 * server-side answer to the dashboard's "% of Portfolio" column, whose target
 * editor writes {@code UserSettings.PortfolioSettings.targets}.
 * <p>
 * <strong>This is the one AI payload carrying converted figures.</strong> A weight
 * is a share of the whole portfolio, and a portfolio that mixes currencies has no
 * such share until its positions are expressed in one of them; leaving that to the
 * caller would mean an AI answer that disagrees with the number on screen. So the
 * percent, drift and {@code ...InBaseCurrency} fields are converted with the same
 * rate table the envelope ships ({@code fxRates}) and the same base currency the
 * SPA uses. Every field <em>without</em> that suffix stays native, as everywhere
 * else in this package.
 */
public record AiAllocationTargets(
        String portfolioId,
        /** Currency the percent/drift maths was done in; the user's display currency. */
        String baseCurrency,
        /** Market value of all positions, converted to baseCurrency. Excludes cash. */
        BigDecimal totalValueInBaseCurrency,
        /** Sum of the targets the user has set, in percent. */
        BigDecimal targetedPercentTotal,
        /** 100 minus targetedPercentTotal — the share of the portfolio left unclaimed; negative when over-committed. */
        BigDecimal untargetedPercent,
        int targetsSet,
        int positionsWithoutTarget,
        /** Targeted rows first, most drifted first; then untargeted positions by value. */
        List<Row> rows) {

    /** ON_TARGET is within {@code +/-1} percentage point, matching the dashboard's TargetPercentCell. */
    public record Row(
            String ticker,
            String name,
            String assetType,
            /** Book currency of the native figures below. */
            String currency,
            /** false when a target survives a position that is no longer held. */
            boolean held,
            BigDecimal shares,
            /** Price per share, native. */
            BigDecimal price,
            BigDecimal marketValue,
            BigDecimal marketValueInBaseCurrency,
            /** Desired share of the whole portfolio in percent; null when the user set none. */
            Double targetPercent,
            /** Actual share of the whole portfolio in percent. */
            BigDecimal currentPercent,
            /** currentPercent minus targetPercent, in percentage points; null without a target. */
            BigDecimal driftPercentagePoints,
            BigDecimal targetValueInBaseCurrency,
            /** Positive means buy this much to reach the target, negative means sell. */
            BigDecimal deltaValueInBaseCurrency,
            /** The same delta in the position's own currency. */
            BigDecimal deltaValue,
            /** Shares to buy (positive) or sell (negative); null when no price is known. */
            BigDecimal deltaShares,
            /** ON_TARGET | UNDERWEIGHT | OVERWEIGHT | NO_TARGET. */
            String status) {
    }
}
