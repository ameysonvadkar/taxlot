package com.taxlot.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import com.taxlot.marketdata.domain.LatestPrice;
import com.taxlot.marketdata.service.MarketDataService;
import com.taxlot.marketdata.service.PriceCache;
import com.taxlot.marketdata.service.PriceSimulator;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * Proves Redis is actually in the read path.
 *
 * <p>The load-bearing test here is {@link #aPoisonedCacheEntryIsServedProvingTheReadCameFromRedis()}.
 * Asserting that the cache gets <em>written</em> is easy and proves nothing — the service would
 * behave identically with the cache write in place and the cache read missing. Writing a value
 * into Redis that could not have come from Postgres, and then getting it back, is the only way to
 * show the read genuinely went to Redis.
 *
 * <p>Deliberately makes no assumption about which prices or dates are present, because these
 * integration tests share one database and {@link PriceSimulationIT} may have already moved it.
 */
@SpringBootTest
class PriceCacheIT extends AbstractIntegrationTest {

    private static final String CACHE_KEY = "market-data:prices:latest";

    @Autowired
    private MarketDataService marketData;

    @Autowired
    private PriceCache cache;

    @Autowired
    private PriceSimulator simulator;

    @Autowired
    private RedisTemplate<String, LatestPrice> redis;

    @BeforeEach
    void startFromAnEmptyCache() {
        cache.evictAll();
    }

    /**
     * Clears up afterwards as well as before.
     *
     * <p>Not symmetry for its own sake: {@link #aPoisonedCacheEntryIsServedProvingTheReadCameFromRedis()}
     * writes a price into Redis that exists nowhere in the database, and Redis is shared with
     * every other integration-test class. Leaving it behind makes an unrelated class fail with a
     * baffling "expected 200.0000 but was 1.2345".
     */
    @AfterEach
    void leaveNoPoisonBehind() {
        cache.evictAll();
    }

    @Test
    @DisplayName("reading all prices warms the cache with every ticker")
    void readingAllPricesPopulatesTheCache() {
        assertThat(redis.hasKey(CACHE_KEY)).isFalse();

        List<LatestPrice> prices = marketData.findAllLatestPrices();

        assertThat(prices).hasSize(30);
        assertThat(redis.<String, LatestPrice>opsForHash().size(CACHE_KEY)).isEqualTo(30);
    }

    @Test
    @DisplayName("a poisoned cache entry is served, proving the read came from Redis")
    void aPoisonedCacheEntryIsServedProvingTheReadCameFromRedis() {
        LatestPrice real = marketData.findLatestPrices(List.of("AAPL")).getFirst();

        // A price that exists nowhere in Postgres. If the service returns it, the value can only
        // have come from Redis.
        LatestPrice poisoned = new LatestPrice("AAPL", new BigDecimal("1.2345"), real.priceDate());
        cache.put(List.of(poisoned));

        LatestPrice served = marketData.findLatestPrices(List.of("AAPL")).getFirst();

        assertThat(served.close()).isEqualByComparingTo("1.2345");
        assertThat(served.close()).isNotEqualByComparingTo(real.close());
    }

    @Test
    @DisplayName("a partial hit fetches only the missing tickers and back-fills them")
    void missingTickersAreLoadedFromTheDatabaseAndCached() {
        marketData.findLatestPrices(List.of("AAPL"));
        assertThat(redis.<String, LatestPrice>opsForHash().size(CACHE_KEY)).isEqualTo(1);

        List<LatestPrice> both = marketData.findLatestPrices(List.of("AAPL", "MSFT"));

        assertThat(both).extracting(LatestPrice::ticker).containsExactly("AAPL", "MSFT");
        assertThat(redis.<String, LatestPrice>opsForHash().size(CACHE_KEY)).isEqualTo(2);
    }

    @Test
    @DisplayName("results come back in the order the caller asked for")
    void resultsFollowTheRequestedTickerOrder() {
        List<LatestPrice> prices = marketData.findLatestPrices(List.of("MSFT", "AAPL", "XOM"));

        assertThat(prices).extracting(LatestPrice::ticker).containsExactly("MSFT", "AAPL", "XOM");
    }

    @Test
    @DisplayName("an unknown ticker is omitted rather than returned as null")
    void unknownTickersAreSimplyAbsent() {
        List<LatestPrice> prices = marketData.findLatestPrices(List.of("AAPL", "NOTREAL"));

        assertThat(prices).extracting(LatestPrice::ticker).containsExactly("AAPL");
    }

    @Test
    @DisplayName("simulating prices evicts the whole cache, because every entry just went stale")
    void simulationEvictsTheCache() {
        marketData.findAllLatestPrices();
        assertThat(redis.<String, LatestPrice>opsForHash().size(CACHE_KEY)).isEqualTo(30);

        simulator.simulate(1);

        assertThat(redis.hasKey(CACHE_KEY)).isFalse();
    }

    @Test
    @DisplayName("the cache round-trips as readable JSON, not a Java-serialised blob")
    void cachedValuesSurviveARoundTrip() {
        LatestPrice original = marketData.findLatestPrices(List.of("JPM")).getFirst();

        Map<String, LatestPrice> readBack = cache.get(List.of("JPM"));

        assertThat(readBack).containsKey("JPM");
        assertThat(readBack.get("JPM").ticker()).isEqualTo(original.ticker());
        assertThat(readBack.get("JPM").close()).isEqualByComparingTo(original.close());
        assertThat(readBack.get("JPM").priceDate()).isEqualTo(original.priceDate());
    }
}
