package com.taxlot.common.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MoneyTest {

    @Nested
    @DisplayName("scale normalisation")
    class Scaling {

        @Test
        void moneyIsHeldAtTwoDecimalPlaces() {
            assertThat(Money.money("170")).hasToString("170.00");
            assertThat(Money.money("170.4")).hasToString("170.40");
        }

        @Test
        void quantityIsHeldAtFourDecimalPlaces() {
            assertThat(Money.quantity("100")).hasToString("100.0000");
        }

        @Test
        void priceIsHeldAtFourDecimalPlaces() {
            assertThat(Money.price("170.5")).hasToString("170.5000");
        }

        @Test
        void weightIsHeldAtSixDecimalPlaces() {
            assertThat(Money.weight("0.05")).hasToString("0.050000");
        }

        @Test
        void nullIsRejectedRatherThanTreatedAsZero() {
            assertThatNullPointerException()
                    .isThrownBy(() -> Money.money((BigDecimal) null))
                    .withMessageContaining("money value must not be null");
        }
    }

    @Nested
    @DisplayName("HALF_EVEN rounding")
    class Rounding {

        /**
         * The defining behaviour of banker's rounding: an exact half goes to the nearest EVEN
         * digit, so it rounds down as often as up. HALF_UP would round every one of these up and
         * bias the total upward across a large batch.
         */
        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
            "2.345, 2.34", // preceding digit 4 is even -> stays
            "2.355, 2.36", // preceding digit 5 is odd  -> rounds up to even 6
            "2.365, 2.36", // preceding digit 6 is even -> stays
            "2.375, 2.38", // preceding digit 7 is odd  -> rounds up to even 8
            "-2.345, -2.34",
            "-2.355, -2.36"
        })
        void exactHalvesRoundToTheNearestEvenDigit(String input, String expected) {
            assertThat(Money.money(input)).hasToString(expected);
        }

        @Test
        void tiesCancelOutInsteadOfAccumulating() {
            // Four exact halves that HALF_UP would all push up, costing a full cent of drift.
            BigDecimal halfEvenTotal = Money.money("0.125")
                    .add(Money.money("0.135"))
                    .add(Money.money("0.145"))
                    .add(Money.money("0.155"));

            // 0.12 + 0.14 + 0.14 + 0.16 = 0.56, against HALF_UP's 0.13+0.14+0.15+0.16 = 0.58
            assertThat(halfEvenTotal).isEqualByComparingTo("0.56");
        }

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({"170.004, 170.00", "170.006, 170.01", "170.994, 170.99", "170.996, 171.00"})
        void ordinaryValuesRoundToTheNearerCent(String input, String expected) {
            assertThat(Money.money(input)).hasToString(expected);
        }
    }

    @Nested
    @DisplayName("marketValue")
    class MarketValue {

        @Test
        void multipliesQuantityByPriceAndRoundsToCents() {
            // The CLAUDE.md worked example: 100 shares of AAPL at 170.00
            assertThat(Money.marketValue(new BigDecimal("100"), new BigDecimal("170.00")))
                    .isEqualByComparingTo("17000.00");
        }

        @Test
        void reproducesTheWorkedExampleCostBasis() {
            // 100 shares bought at 200.00 -> cost basis 20,000.00
            BigDecimal costBasis = Money.marketValue(new BigDecimal("100"), new BigDecimal("200.00"));
            BigDecimal marketValue = Money.marketValue(new BigDecimal("100"), new BigDecimal("170.00"));

            assertThat(costBasis).isEqualByComparingTo("20000.00");
            assertThat(costBasis.subtract(marketValue)).isEqualByComparingTo("3000.00");
        }

        @Test
        void resultDoesNotDependOnTheCallersInputScale() {
            BigDecimal terse = Money.marketValue(new BigDecimal("10"), new BigDecimal("1.5"));
            BigDecimal padded = Money.marketValue(new BigDecimal("10.0000"), new BigDecimal("1.5000"));

            assertThat(terse).isEqualByComparingTo(padded);
        }

        @Test
        void fractionalSharesAtAFractionalPriceStillLandOnACent() {
            // 3.3333 shares at 170.1234 -> 567.0692... -> 567.07
            assertThat(Money.marketValue(new BigDecimal("3.3333"), new BigDecimal("170.1234")))
                    .isEqualByComparingTo("567.07");
        }

        @Test
        void aZeroQuantityIsWorthNothing() {
            assertThat(Money.marketValue(BigDecimal.ZERO, new BigDecimal("170.00")))
                    .isEqualByComparingTo("0.00");
        }
    }

    @Nested
    @DisplayName("weightOf")
    class Weight {

        @Test
        void expressesAPositionAsAFractionOfThePortfolio() {
            // 8,000 of a 100,000 portfolio is 8%
            assertThat(Money.weightOf(new BigDecimal("8000.00"), new BigDecimal("100000.00")))
                    .isEqualByComparingTo("0.080000");
        }

        @Test
        void keepsSixDecimalPlacesForAThirdOfAPortfolio() {
            assertThat(Money.weightOf(new BigDecimal("1.00"), new BigDecimal("3.00")))
                    .isEqualByComparingTo("0.333333");
        }

        @Test
        void aZeroTotalIsRejectedRatherThanReturningZero() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> Money.weightOf(new BigDecimal("100.00"), BigDecimal.ZERO))
                    .withMessageContaining("total value of zero");
        }

        @Test
        void aZeroPositionInANonEmptyPortfolioWeighsNothing() {
            assertThat(Money.weightOf(BigDecimal.ZERO, new BigDecimal("100000.00")))
                    .isEqualByComparingTo("0.000000");
        }
    }

    @Nested
    @DisplayName("comparison helpers")
    class Comparison {

        @Test
        void isEqualIgnoresScaleWhereBigDecimalEqualsDoesNot() {
            BigDecimal one = new BigDecimal("1.0");
            BigDecimal oneToTwoPlaces = new BigDecimal("1.00");

            assertThat(one.equals(oneToTwoPlaces)).isFalse();
            assertThat(Money.isEqual(one, oneToTwoPlaces)).isTrue();
        }

        @Test
        void isZeroIgnoresScale() {
            assertThat(Money.isZero(new BigDecimal("0.0000"))).isTrue();
            assertThat(Money.isZero(Money.ZERO_MONEY)).isTrue();
            assertThat(Money.isZero(new BigDecimal("0.01"))).isFalse();
        }
    }
}
