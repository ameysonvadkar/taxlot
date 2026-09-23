package com.taxlot.marketdata;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Owns the security master: which securities exist, what sector each belongs to, what they cost
 * today, and which security can stand in for which when a position has to be sold for tax reasons.
 *
 * <p>Populated in Phase 1.
 */
@SpringBootApplication
public class MarketDataServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MarketDataServiceApplication.class, args);
    }
}
