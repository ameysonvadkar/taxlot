package com.taxlot.marketdata.service;

import com.taxlot.marketdata.domain.LatestPrice;
import com.taxlot.marketdata.domain.Security;
import com.taxlot.marketdata.repository.PriceRepository;
import com.taxlot.marketdata.repository.SecurityRepository;
import com.taxlot.marketdata.repository.SubstituteRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read access to the security master, prices and the substitute graph. */
@Service
@Transactional(readOnly = true)
public class MarketDataService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataService.class);

    private final SecurityRepository securities;
    private final PriceRepository prices;
    private final SubstituteRepository substitutes;
    private final PriceCache cache;

    public MarketDataService(
            SecurityRepository securities,
            PriceRepository prices,
            SubstituteRepository substitutes,
            PriceCache cache) {
        this.securities = securities;
        this.prices = prices;
        this.substitutes = substitutes;
        this.cache = cache;
    }

    public List<Security> findAllSecurities() {
        return securities.findAllByOrderByTicker();
    }

    public List<Security> findSecuritiesInSector(String sector) {
        return securities.findBySectorOrderByTicker(sector);
    }

    /** Substitute tickers for a security, best first. Empty if the ticker is unknown. */
    public List<String> findSubstitutesFor(String ticker) {
        return substitutes.findSubstituteTickersFor(ticker);
    }

    /**
     * Latest prices for the given tickers, read through the Redis cache.
     *
     * <p>Cache hits are served from Redis; whatever is missing is fetched from Postgres in a
     * single query and written back. An unknown ticker simply does not appear in the result —
     * the caller decides whether that is an error, because for a rebalance it usually is and for
     * an ad-hoc query it usually is not.
     */
    public List<LatestPrice> findLatestPrices(Collection<String> tickers) {
        if (tickers.isEmpty()) {
            return findAllLatestPrices();
        }

        // Copied into a mutable map on purpose. PriceCache.get returns an immutable map — and
        // specifically returns Map.of() when Redis is unreachable — so writing the database
        // results straight back into it would throw UnsupportedOperationException on exactly
        // the path that is supposed to survive Redis being down.
        Map<String, LatestPrice> hits = new LinkedHashMap<>(cache.get(tickers));

        List<String> misses = new ArrayList<>();
        for (String ticker : tickers) {
            if (!hits.containsKey(ticker)) {
                misses.add(ticker);
            }
        }

        if (!misses.isEmpty()) {
            List<LatestPrice> fromDatabase = prices.findLatestPriceForTickers(misses);
            cache.put(fromDatabase);
            for (LatestPrice price : fromDatabase) {
                hits.put(price.ticker(), price);
            }
            log.debug("Latest prices: {} cache hits, {} loaded from database", tickers.size() - misses.size(), fromDatabase.size());
        }

        // Ordered by the caller's ticker list so the response is stable and predictable.
        Map<String, LatestPrice> ordered = new LinkedHashMap<>();
        for (String ticker : tickers) {
            LatestPrice price = hits.get(ticker);
            if (price != null) {
                ordered.put(ticker, price);
            }
        }
        return List.copyOf(ordered.values());
    }

    /**
     * Latest prices for the whole universe.
     *
     * <p>Goes straight to Postgres and then warms the cache. Reading every ticker through the
     * cache would need a full-hash read plus a miss list anyway, and this path is what a batch
     * rebalance calls once at the start of a run.
     */
    public List<LatestPrice> findAllLatestPrices() {
        List<LatestPrice> all = prices.findLatestPricePerSecurity();
        cache.put(all);
        return all;
    }
}
