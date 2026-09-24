package com.taxlot.marketdata.repository;

import com.taxlot.marketdata.domain.LatestPrice;
import com.taxlot.marketdata.domain.Price;
import com.taxlot.marketdata.domain.PriceId;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PriceRepository extends JpaRepository<Price, PriceId> {

    /**
     * The most recent close for every security, whatever date each one last traded on.
     *
     * <p>The correlated subquery finds each security's own maximum date rather than one global
     * maximum. The simulator currently advances every security together, so in practice the
     * dates are identical — but a CSV load in Phase 7 can easily leave one security a day behind,
     * and a global-max query would silently return nothing for it.
     */
    @Query("""
            SELECT new com.taxlot.marketdata.domain.LatestPrice(s.ticker, p.close, p.id.priceDate)
            FROM Price p
            JOIN Security s ON s.id = p.id.securityId
            WHERE p.id.priceDate = (
                SELECT MAX(inner_price.id.priceDate)
                FROM Price inner_price
                WHERE inner_price.id.securityId = p.id.securityId
            )
            ORDER BY s.ticker
            """)
    List<LatestPrice> findLatestPricePerSecurity();

    /** As {@link #findLatestPricePerSecurity()}, narrowed to the given tickers. */
    @Query("""
            SELECT new com.taxlot.marketdata.domain.LatestPrice(s.ticker, p.close, p.id.priceDate)
            FROM Price p
            JOIN Security s ON s.id = p.id.securityId
            WHERE s.ticker IN :tickers
              AND p.id.priceDate = (
                SELECT MAX(inner_price.id.priceDate)
                FROM Price inner_price
                WHERE inner_price.id.securityId = p.id.securityId
            )
            ORDER BY s.ticker
            """)
    List<LatestPrice> findLatestPriceForTickers(@Param("tickers") Collection<String> tickers);

    /** The latest date anywhere in the price history — where the simulator resumes from. */
    @Query("SELECT MAX(p.id.priceDate) FROM Price p")
    Optional<LocalDate> findLatestPriceDate();

    List<Price> findByIdPriceDateOrderByIdSecurityId(LocalDate priceDate);

    long countByIdPriceDate(LocalDate priceDate);
}
