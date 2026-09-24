package com.taxlot.marketdata;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/** The HTTP surface, end to end against real Postgres and Redis. */
@SpringBootTest
@AutoConfigureMockMvc
class MarketDataApiIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("GET /securities returns the whole universe")
    void listsAllSecurities() throws Exception {
        mockMvc.perform(get("/securities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(30)))
                .andExpect(jsonPath("$[0].ticker").value("AAPL"))
                .andExpect(jsonPath("$[0].sector").value("TECH"));
    }

    @Test
    @DisplayName("GET /securities?sector=ENERGY filters to one sector")
    void filtersSecuritiesBySector() throws Exception {
        mockMvc.perform(get("/securities").param("sector", "energy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(5)))
                .andExpect(jsonPath("$[*].sector", org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.is("ENERGY"))));
    }

    @Test
    @DisplayName("GET /securities/{ticker}/substitutes is ranked, best first")
    void returnsRankedSubstitutes() throws Exception {
        mockMvc.perform(get("/securities/AAPL/substitutes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("MSFT"));
    }

    @Test
    @DisplayName("GET /prices/latest with no tickers returns the whole universe")
    void returnsAllLatestPrices() throws Exception {
        mockMvc.perform(get("/prices/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(30)))
                .andExpect(jsonPath("$[0].ticker").exists())
                .andExpect(jsonPath("$[0].close").exists())
                .andExpect(jsonPath("$[0].priceDate").exists());
    }

    @Test
    @DisplayName("GET /prices/latest?tickers= is filtered and case-insensitive")
    void returnsFilteredLatestPrices() throws Exception {
        mockMvc.perform(get("/prices/latest").param("tickers", "aapl,MSFT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(2)))
                .andExpect(jsonPath("$[0].ticker").value("AAPL"))
                .andExpect(jsonPath("$[1].ticker").value("MSFT"));
    }

    @Test
    @DisplayName("POST /prices/simulate advances the history and reports what it wrote")
    void simulatesPrices() throws Exception {
        mockMvc.perform(post("/prices/simulate").param("days", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tradingDays").value(3))
                .andExpect(jsonPath("$.securities").value(30))
                .andExpect(jsonPath("$.pricesWritten").value(90));
    }

    /**
     * A bad parameter must be a 400, not a 500. Anything calling this service should not retry a
     * request that will never succeed, and the status code is how it knows.
     */
    @Test
    @DisplayName("POST /prices/simulate?days=0 is a 400, not a 500")
    void rejectsANonPositiveDayCount() throws Exception {
        mockMvc.perform(post("/prices/simulate").param("days", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /prices/simulate is capped so a typo cannot fill the disk")
    void rejectsAnAbsurdDayCount() throws Exception {
        mockMvc.perform(post("/prices/simulate").param("days", "999999"))
                .andExpect(status().isBadRequest());
    }
}
