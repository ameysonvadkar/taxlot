package com.taxlot.marketdata.web;

import com.taxlot.marketdata.domain.LatestPrice;
import com.taxlot.marketdata.service.MarketDataService;
import com.taxlot.marketdata.service.PriceSimulator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/prices")
@Validated
@Tag(name = "Prices", description = "Latest prices and the price simulator")
public class PriceController {

    private final MarketDataService marketData;
    private final PriceSimulator simulator;

    public PriceController(MarketDataService marketData, PriceSimulator simulator) {
        this.marketData = marketData;
        this.simulator = simulator;
    }

    /**
     * @param tickers comma-separated, e.g. {@code ?tickers=AAPL,MSFT}. Omitted means the whole
     *     universe.
     */
    @GetMapping("/latest")
    @Operation(summary = "Latest close per ticker, served through the Redis cache")
    public List<LatestPrice> latest(@RequestParam(required = false) List<String> tickers) {
        if (tickers == null || tickers.isEmpty()) {
            return marketData.findAllLatestPrices();
        }
        // Normalised and de-duplicated: ?tickers=aapl,AAPL should be one lookup, not two.
        Set<String> normalised = tickers.stream()
                .map(String::trim)
                .filter(ticker -> !ticker.isEmpty())
                .map(String::toUpperCase)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return marketData.findLatestPrices(normalised);
    }

    /**
     * Advances every security's price by {@code days} trading days.
     *
     * <p>Capped at 2,000 days — roughly eight years — because each day writes one row per
     * security, and an unbounded value is an easy way to fill the disk by typo.
     */
    @PostMapping("/simulate")
    @Operation(summary = "Advance simulated prices by N trading days")
    public PriceSimulator.SimulationResult simulate(
            @RequestParam(defaultValue = "1") @Positive @Max(2000) int days) {
        return simulator.simulate(days);
    }
}
