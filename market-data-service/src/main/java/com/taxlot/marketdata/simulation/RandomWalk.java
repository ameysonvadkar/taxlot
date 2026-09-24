package com.taxlot.marketdata.simulation;

import com.taxlot.common.money.Money;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * One day's price movement, as a pure function.
 *
 * <p>The randomness is deliberately <em>not</em> generated in here. {@link #step} takes the
 * random shock as an argument, so the whole class is deterministic and every price path in a
 * test is reproducible. The caller owns the {@code Random}; this class owns the maths.
 *
 * <p>The walk is <b>multiplicative</b>, not additive:
 *
 * <pre>{@code next = current * (1 + drift + volatility * shock)}</pre>
 *
 * <p>An additive walk ({@code next = current + shock}) would move a $26 security and a $580
 * security by the same number of dollars, which is nonsense — a $2 move is noise for META and a
 * 7.7% swing for PFE. It can also walk a price straight through zero into negative territory.
 * A multiplicative walk moves everything in percentage terms, which is how prices actually
 * behave and keeps the result positive for any sane shock.
 */
public final class RandomWalk {

    /** Below this, a security is treated as having bottomed out rather than going to zero. */
    public static final BigDecimal FLOOR_PRICE = new BigDecimal("0.01");

    private final BigDecimal dailyDrift;
    private final BigDecimal dailyVolatility;

    /**
     * @param dailyDrift the average daily return, e.g. {@code 0.0003} for roughly 8% a year
     * @param dailyVolatility one standard deviation of daily return, e.g. {@code 0.012} for
     *     roughly 19% annualised
     */
    public RandomWalk(BigDecimal dailyDrift, BigDecimal dailyVolatility) {
        this.dailyDrift = Objects.requireNonNull(dailyDrift, "dailyDrift must not be null");
        this.dailyVolatility = Objects.requireNonNull(dailyVolatility, "dailyVolatility must not be null");
        if (dailyVolatility.signum() < 0) {
            throw new IllegalArgumentException("dailyVolatility must not be negative: " + dailyVolatility);
        }
    }

    /**
     * Advances a price by one day.
     *
     * @param currentPrice must be positive — a zero or negative price is never a legitimate input
     * @param shock a sample from a standard normal distribution, supplied by the caller
     * @return the next price, at price scale, never below {@link #FLOOR_PRICE}
     */
    public BigDecimal step(BigDecimal currentPrice, double shock) {
        Objects.requireNonNull(currentPrice, "currentPrice must not be null");
        if (currentPrice.signum() <= 0) {
            throw new IllegalArgumentException("currentPrice must be positive but was " + currentPrice);
        }
        if (!Double.isFinite(shock)) {
            throw new IllegalArgumentException("shock must be finite but was " + shock);
        }

        // BigDecimal.valueOf is correct here in a way it would not be for money: `shock` is a
        // statistical sample, not a currency amount, so its binary representation carries no
        // accounting meaning. The `no double` rule guards money, not noise.
        BigDecimal scaledShock = dailyVolatility.multiply(BigDecimal.valueOf(shock));
        BigDecimal growthFactor = BigDecimal.ONE.add(dailyDrift).add(scaledShock);

        BigDecimal next = currentPrice.multiply(growthFactor);
        return Money.price(next.max(FLOOR_PRICE));
    }

    public BigDecimal getDailyDrift() {
        return dailyDrift;
    }

    public BigDecimal getDailyVolatility() {
        return dailyVolatility;
    }
}
