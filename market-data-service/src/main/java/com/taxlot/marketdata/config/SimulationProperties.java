package com.taxlot.marketdata.config;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Price simulation parameters.
 *
 * @param dailyDrift average daily return; {@code 0.0003} compounds to roughly 8% a year
 * @param dailyVolatility one standard deviation of daily return; {@code 0.012} is roughly 19%
 *     annualised, in the range of a real large-cap equity
 * @param seed fixed by default so a demo replays identically and a failing test can be
 *     reproduced. Set to {@code null} for a fresh path on every run.
 */
@ConfigurationProperties(prefix = "taxlot.simulation")
public record SimulationProperties(BigDecimal dailyDrift, BigDecimal dailyVolatility, Long seed) {

    public SimulationProperties {
        if (dailyDrift == null) {
            dailyDrift = new BigDecimal("0.0003");
        }
        if (dailyVolatility == null) {
            dailyVolatility = new BigDecimal("0.012");
        }
    }
}
