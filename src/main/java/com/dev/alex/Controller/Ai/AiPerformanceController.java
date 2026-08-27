package com.dev.alex.Controller.Ai;

import com.dev.alex.Model.NonDbModel.Ai.AiEnvelope;
import com.dev.alex.Model.NonDbModel.Ai.AiPerformanceData;
import com.dev.alex.Model.NonDbModel.PerformanceData;
import com.dev.alex.Model.NonDbModel.PerformancePoint;
import com.dev.alex.Service.Ai.AiEnvelopeService;
import com.dev.alex.Service.PortfolioAccessService;
import com.dev.alex.Service.PortfolioPerformanceServiceImpl;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Performance and profit-and-loss endpoints.
 * <p>
 * The value series is the trustworthy figure here: each point breaks value down
 * per currency and converts cleanly. The headline scalars cannot — they are
 * cross-currency sums with no FX applied — so they are named for what they are.
 */
@RestController
@RequestMapping("/api/v1/ai")
public class AiPerformanceController {

    /** Beyond this a decades-long monthly series stops fitting a model's context usefully. */
    private static final int DEFAULT_MAX_POINTS = 120;

    @Autowired
    private PortfolioPerformanceServiceImpl performanceService;
    @Autowired
    private AiEnvelopeService envelopeService;
    @Autowired
    private PortfolioAccessService portfolioAccessService;

    @Operation(summary = "Portfolio performance",
            description = "Invested, current value, unrealized and realized P&L, dividends, total return "
                    + "and XIRR, with the value series broken down per currency. Scalars suffixed "
                    + "UnconvertedNativeSum are exact only for a single-currency portfolio.")
    @GetMapping("/{portfolioId}/performance")
    public AiEnvelope<AiPerformanceData> performance(
            @PathVariable String portfolioId,
            @RequestParam(defaultValue = "ALL") String period,
            @RequestParam(required = false) Integer maxPoints,
            Authentication authentication) {
        String username = authentication.getName();
        portfolioAccessService.assertOwnership(portfolioId, username);

        PerformanceData raw = performanceService.getPerformance(portfolioId, period);
        List<AiPerformanceData.Point> series = toPoints(
                downsample(raw == null ? null : raw.getTimeSeries(), resolveMaxPoints(maxPoints)));

        AiPerformanceData payload = new AiPerformanceData(
                period,
                raw == null ? null : raw.getTotalInvested(),
                raw == null ? null : raw.getCurrentValue(),
                raw == null ? null : raw.getUnrealizedPnL(),
                raw == null ? null : raw.getUnrealizedPnLPct(),
                raw == null ? null : raw.getRealizedPnL(),
                raw == null ? null : raw.getTotalDividends(),
                raw == null ? null : raw.getTotalReturn(),
                raw == null ? null : raw.getTotalReturnPct(),
                raw == null ? null : raw.getXirr(),
                series);

        return envelopeService.wrap(payload,
                envelopeService.baseCurrency(username, portfolioId, currenciesIn(series)),
                AiEnvelopeService.NOTE_UNCONVERTED_SCALARS);
    }

    @Operation(summary = "Portfolio value history",
            description = "Month-end portfolio value, each point split per currency so it can be converted. "
                    + "Downsampled evenly to maxPoints; the newest point is always kept.")
    @GetMapping("/{portfolioId}/history")
    public AiEnvelope<List<AiPerformanceData.Point>> history(
            @PathVariable String portfolioId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Integer maxPoints,
            Authentication authentication) {
        String username = authentication.getName();
        portfolioAccessService.assertOwnership(portfolioId, username);

        LocalDate fromDate = parseDate(from, "from");
        LocalDate toDate = parseDate(to, "to");

        List<PerformancePoint> raw = performanceService.getMonthlyHistory(portfolioId);
        List<PerformancePoint> filtered = new ArrayList<>();
        if (raw != null) {
            for (PerformancePoint p : raw) {
                if (p.getDate() == null) continue;
                if (fromDate != null && p.getDate().isBefore(fromDate)) continue;
                if (toDate != null && p.getDate().isAfter(toDate)) continue;
                filtered.add(p);
            }
        }
        List<AiPerformanceData.Point> series = toPoints(downsample(filtered, resolveMaxPoints(maxPoints)));
        return envelopeService.wrap(series,
                envelopeService.baseCurrency(username, portfolioId, currenciesIn(series)));
    }

    @Operation(summary = "Realized profit and loss",
            description = "FIFO realized P&L per currency, from positions sold in whole or in part. "
                    + "Unrealized P&L lives on the positions themselves.")
    @GetMapping("/{portfolioId}/realized-pnl")
    public AiEnvelope<Map<String, BigDecimal>> realizedPnl(
            @PathVariable String portfolioId, Authentication authentication) {
        String username = authentication.getName();
        portfolioAccessService.assertOwnership(portfolioId, username);
        Map<String, BigDecimal> pnl = performanceService.getRealizedPnLByCurrency(portfolioId);
        return envelopeService.wrap(pnl,
                envelopeService.baseCurrency(username, portfolioId, pnl == null ? null : pnl.keySet()));
    }

    // ---------------------------------------------------------------- helpers

    private static int resolveMaxPoints(Integer requested) {
        if (requested == null || requested <= 0) return DEFAULT_MAX_POINTS;
        return Math.min(requested, 600);
    }

    /** Evenly thins a series, always keeping the first and last point. */
    static List<PerformancePoint> downsample(List<PerformancePoint> points, int maxPoints) {
        if (points == null || points.isEmpty()) return List.of();
        if (points.size() <= maxPoints) return points;
        int step = (int) Math.ceil((double) points.size() / maxPoints);
        List<PerformancePoint> out = new ArrayList<>();
        for (int i = 0; i < points.size(); i += step) {
            out.add(points.get(i));
        }
        PerformancePoint last = points.get(points.size() - 1);
        if (out.get(out.size() - 1) != last) out.add(last);
        return out;
    }

    /**
     * Drops the flat portfolioValue field on purpose: it is a cross-currency sum
     * kept for older clients, and offering it invites the model to use it.
     */
    private static List<AiPerformanceData.Point> toPoints(List<PerformancePoint> points) {
        List<AiPerformanceData.Point> out = new ArrayList<>(points.size());
        for (PerformancePoint p : points) {
            out.add(new AiPerformanceData.Point(p.getDate(), p.getValueByCurrency()));
        }
        return out;
    }

    private static List<String> currenciesIn(List<AiPerformanceData.Point> series) {
        List<String> currencies = new ArrayList<>();
        for (AiPerformanceData.Point p : series) {
            if (p.valueByCurrency() != null) currencies.addAll(p.valueByCurrency().keySet());
        }
        return currencies;
    }

    private static LocalDate parseDate(String value, String field) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value.trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid " + field + " date '" + value + "'. Expected yyyy-MM-dd.");
        }
    }
}
