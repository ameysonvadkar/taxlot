package com.taxlot.marketdata.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.taxlot.marketdata.domain.LatestPrice;
import com.taxlot.marketdata.repository.PriceRepository;
import com.taxlot.marketdata.repository.SecurityRepository;
import com.taxlot.marketdata.repository.SubstituteRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Cache-interaction behaviour that is awkward to force with a real Redis.
 *
 * <p>The integration tests in {@code PriceCacheIT} prove the cache works when Redis is healthy.
 * These prove what happens when it is not — which is the case that matters most and the one a
 * running container cannot easily reproduce.
 */
@ExtendWith(MockitoExtension.class)
class MarketDataServiceTest {

    @Mock
    private SecurityRepository securities;

    @Mock
    private PriceRepository prices;

    @Mock
    private SubstituteRepository substitutes;

    @Mock
    private PriceCache cache;

    @InjectMocks
    private MarketDataService marketData;

    private static final LatestPrice APPLE =
            new LatestPrice("AAPL", new BigDecimal("200.0000"), LocalDate.of(2026, 1, 2));

    /**
     * Regression test. {@link PriceCache#get} returns an immutable map, and returns
     * {@code Map.of()} specifically when Redis is unreachable. An earlier version wrote the
     * database results back into that map directly, so a Redis outage turned every price lookup
     * into an {@code UnsupportedOperationException} — failing exactly the scenario the fail-soft
     * cache was built to survive.
     */
    @Test
    @DisplayName("Redis being down falls through to the database instead of failing the request")
    void aRedisOutageFallsThroughToTheDatabase() {
        // What PriceCache returns when it cannot reach Redis: an immutable empty map.
        when(cache.get(anyCollection())).thenReturn(Map.of());
        when(prices.findLatestPriceForTickers(any())).thenReturn(List.of(APPLE));

        List<LatestPrice> result = marketData.findLatestPrices(List.of("AAPL"));

        assertThat(result).containsExactly(APPLE);
    }

    @Test
    @DisplayName("a full cache hit does not touch the database at all")
    void aFullCacheHitSkipsTheDatabase() {
        when(cache.get(anyCollection())).thenReturn(Map.of("AAPL", APPLE));

        List<LatestPrice> result = marketData.findLatestPrices(List.of("AAPL"));

        assertThat(result).containsExactly(APPLE);
        verify(prices, never()).findLatestPriceForTickers(any());
    }

    @Test
    @DisplayName("only the missing tickers are fetched, and they get written back to the cache")
    void onlyMissesAreFetchedAndThenCached() {
        LatestPrice microsoft =
                new LatestPrice("MSFT", new BigDecimal("420.0000"), LocalDate.of(2026, 1, 2));

        when(cache.get(anyCollection())).thenReturn(Map.of("AAPL", APPLE));
        when(prices.findLatestPriceForTickers(List.of("MSFT"))).thenReturn(List.of(microsoft));

        List<LatestPrice> result = marketData.findLatestPrices(List.of("AAPL", "MSFT"));

        assertThat(result).containsExactly(APPLE, microsoft);
        // Fetched only the miss, not the hit.
        verify(prices).findLatestPriceForTickers(List.of("MSFT"));
        verify(cache).put(List.of(microsoft));
    }

    @Test
    @DisplayName("an unknown ticker is dropped, not returned as a null entry")
    void unknownTickersAreDroppedRatherThanNull() {
        when(cache.get(anyCollection())).thenReturn(Map.of());
        when(prices.findLatestPriceForTickers(any())).thenReturn(List.of(APPLE));

        List<LatestPrice> result = marketData.findLatestPrices(List.of("AAPL", "NOPE"));

        assertThat(result).containsExactly(APPLE).doesNotContainNull();
    }
}
