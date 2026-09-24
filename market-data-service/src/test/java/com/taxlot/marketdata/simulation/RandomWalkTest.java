package com.taxlot.marketdata.simulation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.math.BigDecimal;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RandomWalkTest {

    /** No drift, no volatility: isolates whatever behaviour is under test from the noise. */
    private static final RandomWalk STILL =
            new RandomWalk(BigDecimal.ZERO, BigDecimal.ZERO);

    /** 1% volatility, no drift: one unit of shock moves the price exactly 1%. */
    private static final RandomWalk ONE_PERCENT =
            new RandomWalk(BigDecimal.ZERO, new BigDecimal("0.01"));

    @Nested
    @DisplayName("the walk is multiplicative, not additive")
    class Multiplicative {

        /**
         * The central design claim. A 1-sigma shock must move a cheap security and an expensive
         * one by the same *percentage*, not the same number of dollars. An additive walk would
         * move both by the same absolute amount, which is a rounding error for META and a large
         * swing for PFE.
         */
        @Test
        void aOneSigmaShockMovesEveryPriceByTheSamePercentage() {
            assertThat(ONE_PERCENT.step(new BigDecimal("100.00"), 1.0)).isEqualByComparingTo("101.0000");
            assertThat(ONE_PERCENT.step(new BigDecimal("500.00"), 1.0)).isEqualByComparingTo("505.0000");
            assertThat(ONE_PERCENT.step(new BigDecimal("26.00"), 1.0)).isEqualByComparingTo("26.2600");
        }

        @Test
        void aNegativeShockMovesThePriceDownByTheSamePercentage() {
            assertThat(ONE_PERCENT.step(new BigDecimal("100.00"), -1.0)).isEqualByComparingTo("99.0000");
            assertThat(ONE_PERCENT.step(new BigDecimal("500.00"), -1.0)).isEqualByComparingTo("495.0000");
        }
    }

    @Nested
    @DisplayName("drift and shock")
    class DriftAndShock {

        @Test
        void aZeroShockWithZeroDriftLeavesThePriceUnchanged() {
            assertThat(STILL.step(new BigDecimal("170.00"), 0.0)).isEqualByComparingTo("170.0000");
        }

        @Test
        void driftAppliesEvenWithNoShock() {
            RandomWalk drifting = new RandomWalk(new BigDecimal("0.01"), BigDecimal.ZERO);
            assertThat(drifting.step(new BigDecimal("100.00"), 0.0)).isEqualByComparingTo("101.0000");
        }

        @Test
        void driftAndShockCombine() {
            // 1% drift + (1% volatility x 2 sigma) = +3%
            RandomWalk walk = new RandomWalk(new BigDecimal("0.01"), new BigDecimal("0.01"));
            assertThat(walk.step(new BigDecimal("100.00"), 2.0)).isEqualByComparingTo("103.0000");
        }
    }

    @Nested
    @DisplayName("bounds and invariants")
    class Bounds {

        /**
         * A shock large enough to drive the growth factor negative must not produce a negative
         * price. A negative price would pass straight into market-value and weight calculations
         * and corrupt everything downstream, rather than failing anywhere visible.
         */
        @Test
        void anExtremeNegativeShockFloorsInsteadOfGoingNegative() {
            BigDecimal floored = ONE_PERCENT.step(new BigDecimal("100.00"), -1000.0);

            assertThat(floored).isEqualByComparingTo(RandomWalk.FLOOR_PRICE);
            assertThat(floored.signum()).isPositive();
        }

        @Test
        void resultIsAlwaysAtPriceScale() {
            // 100 * (1 + 0.01 * 0.333...) does not land on four decimal places by itself.
            assertThat(ONE_PERCENT.step(new BigDecimal("100.00"), 1.0 / 3.0).scale()).isEqualTo(4);
        }

        @Test
        void thePriceStaysPositiveAcrossALongRandomPath() {
            RandomWalk realistic = new RandomWalk(new BigDecimal("0.0003"), new BigDecimal("0.012"));
            Random random = new Random(42);
            BigDecimal price = new BigDecimal("200.00");

            for (int day = 0; day < 2_000; day++) {
                price = realistic.step(price, random.nextGaussian());
                assertThat(price.signum()).isPositive();
            }
        }
    }

    @Nested
    @DisplayName("determinism")
    class Determinism {

        /**
         * The whole reason the shock is a parameter rather than generated internally: identical
         * inputs must give identical output, so a price path in a test is reproducible and a
         * failure can be re-run.
         */
        @Test
        void sameInputsProduceIdenticalOutput() {
            BigDecimal first = ONE_PERCENT.step(new BigDecimal("137.42"), 0.8164);
            BigDecimal second = ONE_PERCENT.step(new BigDecimal("137.42"), 0.8164);

            assertThat(first).isEqualByComparingTo(second);
        }

        @Test
        void aSeededPathReplaysExactly() {
            assertThat(walkFrom(new Random(7))).isEqualTo(walkFrom(new Random(7)));
        }

        private BigDecimal walkFrom(Random random) {
            BigDecimal price = new BigDecimal("100.00");
            for (int day = 0; day < 50; day++) {
                price = ONE_PERCENT.step(price, random.nextGaussian());
            }
            return price;
        }
    }

    @Nested
    @DisplayName("rejects bad input loudly")
    class Validation {

        @ParameterizedTest
        @ValueSource(strings = {"0", "-0.01", "-100"})
        void aNonPositiveCurrentPriceIsRejected(String price) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> STILL.step(new BigDecimal(price), 0.0))
                    .withMessageContaining("must be positive");
        }

        @Test
        void aNullCurrentPriceIsRejected() {
            assertThatNullPointerException().isThrownBy(() -> STILL.step(null, 0.0));
        }

        @ParameterizedTest
        @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
        void aNonFiniteShockIsRejected(double shock) {
            // BigDecimal.valueOf(NaN) throws a bare NumberFormatException with no context;
            // catching it here produces an error that names the actual problem.
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> STILL.step(new BigDecimal("100.00"), shock))
                    .withMessageContaining("finite");
        }

        @Test
        void negativeVolatilityIsRejectedAtConstruction() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new RandomWalk(BigDecimal.ZERO, new BigDecimal("-0.01")))
                    .withMessageContaining("must not be negative");
        }
    }
}
