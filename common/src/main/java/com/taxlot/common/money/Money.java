package com.taxlot.common.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * The single place where decimal scale and rounding are decided.
 *
 * <p>Every monetary amount, share quantity, price and portfolio weight in TaxLot passes through
 * here. Centralising it is the point: if rounding lived at each call site, two code paths would
 * eventually disagree about what {@code 100 shares x $170.005} is worth, and a rebalance would
 * produce trades that don't reconcile against the cash balance.
 *
 * <p>Rounding is always {@link RoundingMode#HALF_EVEN} ("banker's rounding"). Unlike HALF_UP, it
 * breaks ties toward the even digit, so rounding errors cancel out across many operations instead
 * of accumulating upward. Over thousands of lots in a batch rebalance that difference is real
 * money, and it is the convention financial systems expect.
 *
 * <p>{@code double} is never used anywhere in this codebase. {@code 0.1 + 0.2 != 0.3} in binary
 * floating point, and a cost basis that is off by a fraction of a cent produces a wrong realised
 * gain, which produces a wrong tax figure.
 */
public final class Money {

    /** Currency amounts: cents. Matches {@code NUMERIC(18,2)} in the schema. */
    public static final int MONEY_SCALE = 2;

    /** Share quantities. Matches {@code NUMERIC(18,4)} in the schema. */
    public static final int QUANTITY_SCALE = 4;

    /** Security prices. Matches {@code NUMERIC(18,4)} in the schema. */
    public static final int PRICE_SCALE = 4;

    /** Portfolio weights, as a fraction of 1. Matches {@code NUMERIC(9,6)} in the schema. */
    public static final int WEIGHT_SCALE = 6;

    public static final RoundingMode ROUNDING = RoundingMode.HALF_EVEN;

    /** Zero at money scale, for use as an accumulator seed. */
    public static final BigDecimal ZERO_MONEY = BigDecimal.ZERO.setScale(MONEY_SCALE);

    private Money() {
        // utility class
    }

    /** Normalises an amount to money scale. */
    public static BigDecimal money(BigDecimal value) {
        return scale(value, MONEY_SCALE, "money");
    }

    /** Normalises a literal to money scale. Convenient in tests and seed data. */
    public static BigDecimal money(String value) {
        return money(new BigDecimal(Objects.requireNonNull(value, "money value must not be null")));
    }

    /** Normalises a share quantity to quantity scale. */
    public static BigDecimal quantity(BigDecimal value) {
        return scale(value, QUANTITY_SCALE, "quantity");
    }

    /** Normalises a share quantity literal to quantity scale. */
    public static BigDecimal quantity(String value) {
        return quantity(new BigDecimal(Objects.requireNonNull(value, "quantity value must not be null")));
    }

    /** Normalises a price to price scale. */
    public static BigDecimal price(BigDecimal value) {
        return scale(value, PRICE_SCALE, "price");
    }

    /** Normalises a price literal to price scale. */
    public static BigDecimal price(String value) {
        return price(new BigDecimal(Objects.requireNonNull(value, "price value must not be null")));
    }

    /** Normalises a portfolio weight to weight scale. */
    public static BigDecimal weight(BigDecimal value) {
        return scale(value, WEIGHT_SCALE, "weight");
    }

    /** Normalises a portfolio weight literal to weight scale. */
    public static BigDecimal weight(String value) {
        return weight(new BigDecimal(Objects.requireNonNull(value, "weight value must not be null")));
    }

    /**
     * What a holding is worth: quantity x price, rounded to money scale.
     *
     * <p>Inputs are normalised to their own scales before multiplying, so the result does not
     * depend on how many decimal places the caller happened to pass in.
     */
    public static BigDecimal marketValue(BigDecimal quantity, BigDecimal pricePerShare) {
        BigDecimal q = quantity(quantity);
        BigDecimal p = price(pricePerShare);
        return money(q.multiply(p));
    }

    /**
     * A position's share of the portfolio, as a fraction of 1 at weight scale.
     *
     * @throws IllegalArgumentException if {@code totalValue} is zero — a weight against an empty
     *     portfolio has no meaning, and returning zero here would silently hide the caller's bug
     */
    public static BigDecimal weightOf(BigDecimal positionValue, BigDecimal totalValue) {
        BigDecimal position = money(positionValue);
        BigDecimal total = money(totalValue);
        if (total.signum() == 0) {
            throw new IllegalArgumentException("cannot compute a weight against a total value of zero");
        }
        return position.divide(total, WEIGHT_SCALE, ROUNDING);
    }

    /** True when the value is zero, regardless of the scale it arrived at. */
    public static boolean isZero(BigDecimal value) {
        return Objects.requireNonNull(value, "value must not be null").signum() == 0;
    }

    /**
     * Compares two decimals by value, ignoring scale.
     *
     * <p>{@code BigDecimal.equals} treats {@code 1.0} and {@code 1.00} as different objects, which
     * is almost never what domain code means. This is the comparison to reach for instead.
     */
    public static boolean isEqual(BigDecimal left, BigDecimal right) {
        Objects.requireNonNull(left, "left must not be null");
        Objects.requireNonNull(right, "right must not be null");
        return left.compareTo(right) == 0;
    }

    private static BigDecimal scale(BigDecimal value, int scale, String label) {
        Objects.requireNonNull(value, label + " value must not be null");
        return value.setScale(scale, ROUNDING);
    }
}
