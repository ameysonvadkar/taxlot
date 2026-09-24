package com.taxlot.marketdata.repository;

import com.taxlot.marketdata.domain.Security;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SecurityRepository extends JpaRepository<Security, Long> {

    Optional<Security> findByTicker(String ticker);

    List<Security> findByTickerInOrderByTicker(Collection<String> tickers);

    List<Security> findAllByOrderByTicker();

    List<Security> findBySectorOrderByTicker(String sector);
}
