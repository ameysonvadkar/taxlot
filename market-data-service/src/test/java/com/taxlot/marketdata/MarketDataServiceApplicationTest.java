package com.taxlot.marketdata;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Catches broken wiring — a missing bean, a clashing component scan, malformed YAML — at build
 * time rather than at startup on a server.
 */
@SpringBootTest
class MarketDataServiceApplicationTest {

    @Test
    void contextLoads() {
        // The assertion is that @SpringBootTest managed to build the context at all.
    }
}
