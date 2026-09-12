package com.dev.alex.Service.Ai;

import com.dev.alex.Model.NonDbModel.Ai.AiEnvelope;
import com.dev.alex.Model.UserSettings;
import com.dev.alex.Service.FxRateServiceImpl;
import com.dev.alex.Service.UserProfileServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the {@link AiEnvelope} every /api/v1/ai/** response ships in.
 * <p>
 * The API never converts currency (CLAUDE.md: FX is the presentation layer's
 * job), so an AI caller has to do it. This service supplies the two things that
 * makes possible: the rate table and the formula, plus the caveats that apply
 * to the payload.
 */
@Service
public class AiEnvelopeService {

    /** How a caller turns a native amount into any other currency. */
    public static final String CONVERSION_FORMULA =
            "amount_in_TARGET = amount * fxRates[TARGET] / fxRates[SOURCE]";

    public static final String NOTE_NATIVE_CURRENCY =
            "Every money figure is in its own native currency, named by the currency field beside it. "
                    + "Nothing here is converted. Use fxRates and conversionFormula to convert, and never "
                    + "add two amounts whose currency codes differ.";

    public static final String NOTE_PENCE =
            "'GBp'/'GBx' mean pence, not pounds: 100 GBp = 1 GBP. fxRates already contains entries for them "
                    + "(GBP rate x 100), so the formula handles pence with no special case.";

    public static final String NOTE_NULLS =
            "null means 'not reported', never zero. Do not substitute 0 for a missing figure.";

    public static final String NOTE_STATISTICS_SCALING =
            "Scaling inside statistics is Yahoo's and is not uniform: margins, growth rates, returns, "
                    + "payoutRatio, trailingAnnualDividendYield, shortPercentOfFloat, heldPercentInsiders, "
                    + "heldPercentInstitutions and fiftyTwoWeekChange are fractions (0.3934 = 39.34%), while "
                    + "dividendYield, fiveYearAvgDividendYield and debtToEquity are already percentages "
                    + "(0.93 = 0.93%).";

    public static final String NOTE_UNCONVERTED_SCALARS =
            "Fields suffixed UnconvertedNativeSum were summed across currencies without FX. They are exact "
                    + "only for a single-currency portfolio; for a mixed one, rebuild them from timeSeries "
                    + "valueByCurrency or from the per-currency totals in the snapshot.";

    public static final String NOTE_SPARSE_SHARES =
            "sharesHistory is sparse: a point exists only where the share count changed. Carry the last "
                    + "value forward rather than interpolating between points.";

    public static final String NOTE_CASH_BALANCE =
            "derivedCashBalance currently sums DEPOSIT and WITHDRAWAL transactions only — BUY, SELL, "
                    + "DIVIDEND and TAX cash flows are not yet included, so treat it as a deposit ledger "
                    + "rather than a true cash balance. manualCash is the user-entered figure.";

    public static final String NOTE_ALLOCATION_TARGETS =
            "Allocation targets are the one payload here that is converted: currentPercent, "
                    + "driftPercentagePoints and every field suffixed InBaseCurrency were computed in "
                    + "baseCurrency at the fxRates above, because a share of the whole portfolio does not "
                    + "exist while unlike currencies sit unconverted. marketValue, deltaValue and price stay "
                    + "native. The denominator is the market value of the positions only — no cash — which "
                    + "is what the dashboard's '% of Portfolio' column shows. A target is the user's "
                    + "intent, not a recommendation: deltaValue/deltaShares describe the trade that would "
                    + "reach it, they do not endorse it.";

    /** Matches the frontend's staleness threshold on the holdings dashboard. */
    public static final int STALE_DAYS = 4;

    @Autowired
    private FxRateServiceImpl fxRateService;
    @Autowired
    private UserProfileServiceImpl userProfileService;

    /**
     * Current rates plus synthetic pence entries, so a caller never has to know
     * the GBp rule. Mirrors FxRateService.getRateForCurrency and the frontend's
     * rateOf() — this is the one place the x100 lives on the AI path.
     */
    public Map<String, BigDecimal> fxRates() {
        Map<String, BigDecimal> rates = new HashMap<>(fxRateService.getAllRatesAsMap());
        BigDecimal gbp = rates.get("GBP");
        if (gbp != null) {
            BigDecimal pence = gbp.multiply(BigDecimal.valueOf(100));
            rates.put("GBp", pence);
            rates.put("GBx", pence);
        }
        rates.putIfAbsent("EUR", BigDecimal.ONE);
        return rates;
    }

    /**
     * The currency the user reads totals in: their explicit setting, else the
     * portfolio's single currency, else USD. Same rule the SPA applies, so an
     * answer phrased in this currency matches what they see on screen.
     */
    public String baseCurrency(String username, String portfolioId, Collection<String> heldCurrencies) {
        String configured = portfolioSettings(username, portfolioId)
                .map(UserSettings.PortfolioSettings::getBaseCurrency)
                .orElse(null);
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        if (heldCurrencies != null) {
            Set<String> distinct = new LinkedHashSet<>();
            for (String c : heldCurrencies) {
                if (c != null && !c.isBlank()) {
                    distinct.add(normalizeCurrency(c));
                }
            }
            if (distinct.size() == 1) {
                return distinct.iterator().next();
            }
        }
        return "USD";
    }

    /** Pence collapse to pounds for the purpose of naming a display currency only. */
    public static String normalizeCurrency(String code) {
        if (code == null || code.isBlank()) return "USD";
        return "GBp".equals(code) || "GBx".equals(code) ? "GBP" : code;
    }

    public java.util.Optional<UserSettings.PortfolioSettings> portfolioSettings(String username, String portfolioId) {
        UserSettings settings = userProfileService.getSettings(username);
        if (settings == null || settings.getPortfolioSettings() == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.ofNullable(settings.getPortfolioSettings().get(portfolioId));
    }

    public <T> AiEnvelope<T> wrap(T data, String baseCurrency, String... notes) {
        List<String> all = new ArrayList<>();
        all.add(NOTE_NATIVE_CURRENCY);
        all.add(NOTE_PENCE);
        all.add(NOTE_NULLS);
        for (String n : notes) {
            if (n != null && !n.isBlank()) all.add(n);
        }
        return new AiEnvelope<>(data, fxRates(), baseCurrency, CONVERSION_FORMULA, all);
    }
}
