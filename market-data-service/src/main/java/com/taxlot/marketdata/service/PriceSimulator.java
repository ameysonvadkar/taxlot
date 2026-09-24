package com.taxlot.marketdata.service;

import com.taxlot.marketdata.config.SimulationProperties;
import com.taxlot.marketdata.domain.Price;
import com.taxlot.marketdata.domain.Security;
import com.taxlot.marketdata.repository.PriceRepository;
import com.taxlot.marketdata.repository.SecurityRepository;
import com.taxlot.marketdata.simulation.RandomWalk;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Advances simulated prices forward from wherever the history currently ends.
 *
 * <p>Deliberately generates weekdays only. Markets do not trade at weekends, and a history with
 * Saturday closes would give the wash-sale window — which counts calendar days — a different
 * shape in tests than in the real rule it is meant to model.
 */
@Service
public class PriceSimulator {

    private static final Logger log = LoggerFactory.getLogger(PriceSimulator.class);

    private final SecurityRepository securities;
    private final PriceRepository prices;
    private final PriceCache cache;
    private final RandomWalk randomWalk;
    private final Long seed;

    public PriceSimulator(
            SecurityRepository securities,
            PriceRepository prices,
            PriceCache cache,
            SimulationProperties properties) {
        this.securities = securities;
        this.prices = prices;
        this.cache = cache;
        this.randomWalk = new RandomWalk(properties.dailyDrift(), properties.dailyVolatility());
        this.seed = properties.seed();
    }

    /**
     * Generates {@code days} further trading days of prices for every security.
     *
     * @param days how many trading days to advance; must be positive
     * @return a summary of what was written
     * @throws IllegalStateException if there is no existing price history to walk forward from —
     *     the simulator extends a series, it does not invent a starting point
     */
    @Transactional
    public SimulationResult simulate(int days) {
        if (days <= 0) {
            throw new IllegalArgumentException("days must be positive but was " + days);
        }

        List<Security> universe = securities.findAllByOrderByTicker();
        if (universe.isEmpty()) {
            throw new IllegalStateException("cannot simulate prices: the security universe is empty");
        }

        LocalDate lastDate = prices.findLatestPriceDate()
                .orElseThrow(() -> new IllegalStateException(
                        "cannot simulate prices: no existing price history to extend"));

        Map<Long, BigDecimal> currentPrices = loadPricesOn(lastDate, universe.size());

        // Seeded when configured, so a demo and a failing test both replay exactly.
        //
        // The seed is mixed with the starting date rather than used directly. With a bare
        // `new Random(seed)`, every call draws the identical sequence of shocks, so calling
        // simulate(1) repeatedly applies the exact same move to each security every time —
        // AAPL would step in the same direction on every call, which is a systematic drift
        // wearing a random walk's clothes. Mixing in the date keeps each run reproducible
        // while making consecutive runs genuinely different from one another.
        Random random = seed == null ? new Random() : new Random(seed + lastDate.toEpochDay());

        List<Price> generated = new ArrayList<>(days * universe.size());
        LocalDate date = lastDate;

        for (int day = 0; day < days; day++) {
            date = nextTradingDay(date);
            for (Security security : universe) {
                BigDecimal previous = currentPrices.get(security.getId());
                if (previous == null) {
                    // A security with no price on the anchor date cannot be walked forward.
                    // Skipping is right: inventing a starting price would fabricate history.
                    continue;
                }
                BigDecimal next = randomWalk.step(previous, random.nextGaussian());
                currentPrices.put(security.getId(), next);
                generated.add(new Price(security.getId(), date, next));
            }
        }

        prices.saveAll(generated);

        // Every security's latest price just changed, so the entire cache is stale at once.
        cache.evictAll();

        log.info("Simulated {} trading days for {} securities: {} -> {} ({} rows)",
                days, universe.size(), lastDate, date, generated.size());

        return new SimulationResult(days, universe.size(), lastDate, date, generated.size());
    }

    private Map<Long, BigDecimal> loadPricesOn(LocalDate date, int expectedSize) {
        Map<Long, BigDecimal> bySecurity = new HashMap<>(expectedSize);
        for (Price price : prices.findByIdPriceDateOrderByIdSecurityId(date)) {
            bySecurity.put(price.getSecurityId(), price.getClose());
        }
        return bySecurity;
    }

    /** The next weekday, skipping Saturday and Sunday. */
    private static LocalDate nextTradingDay(LocalDate from) {
        LocalDate next = from.plusDays(1);
        while (next.getDayOfWeek() == DayOfWeek.SATURDAY || next.getDayOfWeek() == DayOfWeek.SUNDAY) {
            next = next.plusDays(1);
        }
        return next;
    }

    /** What a simulation run produced. */
    public record SimulationResult(
            int tradingDays, int securities, LocalDate fromDate, LocalDate toDate, int pricesWritten) {}
}
