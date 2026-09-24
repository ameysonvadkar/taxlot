package com.taxlot.marketdata.service;

import com.taxlot.marketdata.domain.LatestPrice;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis-backed cache of the latest close per ticker.
 *
 * <p>Stored as a single Redis hash keyed by ticker. A hash rather than one key per ticker because
 * a rebalance asks for thirty tickers at once, and {@code HMGET} answers that in one round trip
 * where thirty {@code GET}s would take thirty.
 *
 * <p><b>Every operation fails soft.</b> If Redis is unreachable, reads report a miss and writes
 * are dropped, and the caller falls through to Postgres. A cache that takes the service down when
 * it fails is worse than no cache — prices are always recomputable from the database, so there is
 * nothing here worth failing a request over.
 */
@Component
public class PriceCache {

    static final String KEY = "market-data:prices:latest";

    private static final Logger log = LoggerFactory.getLogger(PriceCache.class);

    private final RedisTemplate<String, LatestPrice> redis;

    public PriceCache(RedisTemplate<String, LatestPrice> redis) {
        this.redis = redis;
    }

    /**
     * Looks up the given tickers.
     *
     * @return the entries that were present; tickers missing from the result are cache misses and
     *     must be resolved from the database by the caller
     */
    public Map<String, LatestPrice> get(Collection<String> tickers) {
        if (tickers.isEmpty()) {
            return Map.of();
        }
        List<String> requested = new ArrayList<>(tickers);
        try {
            HashOperations<String, String, LatestPrice> hash = redis.opsForHash();
            List<LatestPrice> found = hash.multiGet(KEY, requested);

            Map<String, LatestPrice> hits = new LinkedHashMap<>();
            for (int i = 0; i < requested.size(); i++) {
                LatestPrice price = found.get(i);
                // multiGet returns a null placeholder per missing field, preserving position.
                if (price != null) {
                    hits.put(requested.get(i), price);
                }
            }
            return hits;
        } catch (DataAccessException e) {
            log.warn("Redis unavailable, treating as a cache miss for {} tickers: {}", requested.size(), e.getMessage());
            return Map.of();
        }
    }

    /** Writes prices into the cache, overwriting any existing entry for the same ticker. */
    public void put(Collection<LatestPrice> prices) {
        if (prices.isEmpty()) {
            return;
        }
        try {
            Map<String, LatestPrice> byTicker = new LinkedHashMap<>();
            for (LatestPrice price : prices) {
                byTicker.put(price.ticker(), price);
            }
            redis.<String, LatestPrice>opsForHash().putAll(KEY, byTicker);
        } catch (DataAccessException e) {
            log.warn("Redis unavailable, skipping cache write of {} prices: {}", prices.size(), e.getMessage());
        }
    }

    /**
     * Drops the whole cache.
     *
     * <p>Called after a simulation run. Deleting is correct rather than lazy: the simulator has
     * just changed the latest price for every security, so every entry is stale at once and there
     * is nothing worth keeping.
     */
    public void evictAll() {
        try {
            redis.delete(KEY);
        } catch (DataAccessException e) {
            log.warn("Redis unavailable, could not evict the price cache: {}", e.getMessage());
        }
    }
}
