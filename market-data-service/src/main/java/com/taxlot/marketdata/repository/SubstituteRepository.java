package com.taxlot.marketdata.repository;

import com.taxlot.marketdata.domain.Substitute;
import com.taxlot.marketdata.domain.SubstituteId;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SubstituteRepository extends JpaRepository<Substitute, SubstituteId> {

    List<Substitute> findByIdSecurityIdOrderByRank(Long securityId);

    /**
     * Substitutes for a security, by ticker, best first.
     *
     * <p>Returned as tickers rather than ids because the engine and the other services speak in
     * tickers — ids are an implementation detail of this service's schema.
     */
    @Query("""
            SELECT target.ticker
            FROM Substitute sub
            JOIN Security source ON source.id = sub.id.securityId
            JOIN Security target ON target.id = sub.id.substituteId
            WHERE source.ticker = :ticker
            ORDER BY sub.rank
            """)
    List<String> findSubstituteTickersFor(@Param("ticker") String ticker);
}
