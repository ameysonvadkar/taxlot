package com.taxlot.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.taxlot.marketdata.domain.LatestPrice;
import com.taxlot.marketdata.repository.PriceRepository;
import com.taxlot.marketdata.service.MarketDataService;
import com.taxlot.marketdata.service.PriceCache;
import com.taxlot.marketdata.service.PriceSimulator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The price simulator against a real database.
 *
 * <p>Ordered, because simulation is cumulative: these tests deliberately build on each other's
 * state rather than resetting between methods, which is also how the service behaves in a demo —
 * you call {@code /prices/simulate} repeatedly and the history extends.
 *
 * <p>The containers are shared across every integration-test class (see
 * {@link AbstractIntegrationTest}), and other classes in the suite also run simulations. This
 * class therefore rewinds the price history to the seeded anchor date before it starts, so its
 * absolute-date assertions hold no matter which classes ran first. Asserting on exact dates is
 * worth that cost: "the 30th trading day after a Friday is six calendar weeks later" is precisely
 * the property that would silently break if weekend skipping regressed.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PriceSimulationIT extends AbstractIntegrationTest {

    /** The anchor date in V2__seed_security_universe.sql. A Friday, deliberately. */
    private static final LocalDate SEED_DATE = LocalDate.of(2026, 1, 2);

    private static final int UNIVERSE_SIZE = 30;

    @Autowired
    private PriceSimulator simulator;

    @Autowired
    private MarketDataService marketData;

    @Autowired
    private PriceRepository prices;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PriceCache cache;

    /**
     * Rewinds price history to the seeded anchor date.
     *
     * <p>Deletes rather than re-seeds, so the securities and substitutes are untouched and only
     * the simulated history is discarded. Non-static because {@code PER_CLASS} lifecycle lets
     * {@code @BeforeAll} use injected dependencies.
     */
    @BeforeAll
    void rewindToTheSeededHistory() {
        jdbc.update("DELETE FROM market_data.price WHERE price_date > ?", SEED_DATE);
        // Redis is shared across the suite too, and holds prices for dates that no longer exist
        // after the rewind above.
        cache.evictAll();
    }

    @Test
    @Order(1)
    @DisplayName("the seed leaves exactly one price per security, with AAPL at the worked-example price")
    void seededPricesAreTheStartingPoint() {
        assertThat(prices.findLatestPriceDate()).contains(SEED_DATE);
        assertThat(prices.countByIdPriceDate(SEED_DATE)).isEqualTo(UNIVERSE_SIZE);

        // 200.00 is the purchase price in the CLAUDE.md worked example.
        assertThat(latestFor("AAPL").close()).isEqualByComparingTo("200.0000");
    }

    @Test
    @Order(2)
    @DisplayName("simulating one day from a Friday lands on the Monday, not the Saturday")
    void theSimulatorSkipsWeekends() {
        assertThat(SEED_DATE.getDayOfWeek()).isEqualTo(DayOfWeek.FRIDAY);

        PriceSimulator.SimulationResult result = simulator.simulate(1);

        assertThat(result.fromDate()).isEqualTo(SEED_DATE);
        assertThat(result.toDate()).isEqualTo(LocalDate.of(2026, 1, 5));
        assertThat(result.toDate().getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(result.pricesWritten()).isEqualTo(UNIVERSE_SIZE);
    }

    @Test
    @Order(3)
    @DisplayName("a 30-day run writes 30 trading days for every security and never a weekend")
    void simulatingThirtyDaysAdvancesTheWholeUniverse() {
        LocalDate before = prices.findLatestPriceDate().orElseThrow();
        long rowsBefore = prices.count();

        PriceSimulator.SimulationResult result = simulator.simulate(30);

        assertThat(result.tradingDays()).isEqualTo(30);
        assertThat(result.securities()).isEqualTo(UNIVERSE_SIZE);
        assertThat(result.pricesWritten()).isEqualTo(30 * UNIVERSE_SIZE);
        assertThat(prices.count()).isEqualTo(rowsBefore + 30L * UNIVERSE_SIZE);

        LocalDate after = prices.findLatestPriceDate().orElseThrow();
        assertThat(after).isAfter(before);

        // 30 trading days spans six calendar weeks, so this would fail loudly if weekend
        // skipping regressed.
        assertThat(after).isEqualTo(LocalDate.of(2026, 2, 16));

        assertThat(prices.findAll())
                .extracting(price -> price.getPriceDate().getDayOfWeek())
                .doesNotContain(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
    }

    @Test
    @Order(4)
    @DisplayName("prices actually moved, and stayed positive")
    void simulatedPricesMoveAwayFromTheSeed() {
        List<LatestPrice> latest = marketData.findAllLatestPrices();

        assertThat(latest).hasSize(UNIVERSE_SIZE);
        assertThat(latest).allSatisfy(price ->
                assertThat(price.close()).isGreaterThan(BigDecimal.ZERO));

        // With 31 days of 1.2% daily volatility, every one of 30 securities landing on exactly
        // its seed price is not plausible — if this passes, the walk is not walking.
        assertThat(latest)
                .extracting(LatestPrice::close)
                .doesNotContain(new BigDecimal("200.0000"));

        assertThat(latest).allSatisfy(price ->
                assertThat(price.priceDate()).isEqualTo(LocalDate.of(2026, 2, 16)));
    }

    /**
     * Regression test for a seeding flaw.
     *
     * <p>The simulator builds its {@code Random} per call. Seeding it with the configured seed
     * alone meant every call drew the identical shock sequence, so three consecutive one-day runs
     * multiplied AAPL by the <em>same factor</em> three times — a systematic drift, not a random
     * walk.
     *
     * <p>Compares day-over-day ratios rather than prices, because only the ratio isolates the
     * shock from the price level it was applied to. The ratios are rounded to 5 decimal places
     * first, and that scale is load-bearing: prices are stored at 4 decimal places, so each step
     * carries up to ~2e-7 of relative rounding noise. At full precision that noise makes even
     * identical shocks produce slightly different ratios and the test passes when it should fail
     * — which is exactly what the first version of this test did. 1e-5 sits well above the noise
     * and far below a real 1.2%-volatility move, so the comparison sees the shock and nothing else.
     */
    @Test
    @Order(5)
    @DisplayName("consecutive runs draw different shocks, not the same one repeatedly")
    void repeatedRunsDoNotReplayTheSameShock() {
        BigDecimal previous = latestFor("AAPL").close();
        List<BigDecimal> dailyRatios = new ArrayList<>();
        for (int run = 0; run < 3; run++) {
            simulator.simulate(1);
            BigDecimal next = latestFor("AAPL").close();
            dailyRatios.add(next.divide(previous, 5, RoundingMode.HALF_EVEN));
            previous = next;
        }

        assertThat(dailyRatios)
                .as("identical ratios would mean the same shock was replayed every run")
                .doesNotHaveDuplicates();
    }

    @Test
    @Order(6)
    @DisplayName("a non-positive day count is rejected rather than silently doing nothing")
    void theSimulatorRejectsANonPositiveDayCount() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> simulator.simulate(0))
                .withMessageContaining("must be positive");

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> simulator.simulate(-5))
                .withMessageContaining("must be positive");
    }

    private LatestPrice latestFor(String ticker) {
        return marketData.findLatestPrices(List.of(ticker)).getFirst();
    }
}
