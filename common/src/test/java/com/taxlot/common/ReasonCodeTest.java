package com.taxlot.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ReasonCodeTest {

    @Test
    void everyReasonCodeInClaudeMdIsRepresented() {
        assertThat(ReasonCode.values())
                .containsExactlyInAnyOrder(
                        ReasonCode.TLH_SELL,
                        ReasonCode.TLH_SUB_BUY,
                        ReasonCode.DRIFT_SELL,
                        ReasonCode.DRIFT_BUY);
    }

    @Test
    void sellReasonsPairWithSellAndBuyReasonsWithBuy() {
        assertThat(ReasonCode.TLH_SELL.side()).isEqualTo(Side.SELL);
        assertThat(ReasonCode.DRIFT_SELL.side()).isEqualTo(Side.SELL);
        assertThat(ReasonCode.TLH_SUB_BUY.side()).isEqualTo(Side.BUY);
        assertThat(ReasonCode.DRIFT_BUY.side()).isEqualTo(Side.BUY);
    }

    @ParameterizedTest
    @EnumSource(ReasonCode.class)
    void everyReasonHasExactlyOneValidSide(ReasonCode reasonCode) {
        assertThat(reasonCode.side()).isNotNull();
    }

    @Test
    void onlyTheHarvestingPairIsFlaggedAsTaxLossHarvesting() {
        assertThat(ReasonCode.TLH_SELL.isTaxLossHarvesting()).isTrue();
        assertThat(ReasonCode.TLH_SUB_BUY.isTaxLossHarvesting()).isTrue();
        assertThat(ReasonCode.DRIFT_SELL.isTaxLossHarvesting()).isFalse();
        assertThat(ReasonCode.DRIFT_BUY.isTaxLossHarvesting()).isFalse();
    }
}
