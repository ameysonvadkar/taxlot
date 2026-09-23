package com.taxlot.portfolio;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Owns client accounts and their tax lots: what was bought, when, at what price, what is still
 * open, and what gain or loss was realised when a lot was closed.
 *
 * <p>This service is the system of record for anything with tax consequences. The rebalance engine
 * proposes; this service is what actually changes a client's position.
 *
 * <p>Populated in Phase 2.
 */
@SpringBootApplication
public class PortfolioServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PortfolioServiceApplication.class, args);
    }
}
