package com.taxlot.common;

/**
 * Why the engine proposed a trade.
 *
 * <p>Every proposed trade carries one of these, so a reviewer can tell at a glance whether a
 * trade exists to harvest a loss or to correct drift. The pairs matter: a {@link #TLH_SELL} is
 * always accompanied by a {@link #TLH_SUB_BUY} that keeps the account's market exposure intact.
 */
public enum ReasonCode {

    /** Sell a lot to realise a loss for tax purposes. */
    TLH_SELL(Side.SELL),

    /** Buy a substitute security to replace exposure lost to a {@link #TLH_SELL}. */
    TLH_SUB_BUY(Side.BUY),

    /** Sell down a position that has drifted above its target weight. */
    DRIFT_SELL(Side.SELL),

    /** Buy into a position that has drifted below its target weight. */
    DRIFT_BUY(Side.BUY);

    private final Side side;

    ReasonCode(Side side) {
        this.side = side;
    }

    /**
     * The only side this reason can legitimately appear with.
     *
     * <p>Pairing the two here means a mismatched trade — say a {@code TLH_SELL} recorded as a
     * BUY — can be caught by a single assertion rather than by convention.
     */
    public Side side() {
        return side;
    }

    /** True when this reason came from the tax-loss harvesting pass rather than the drift pass. */
    public boolean isTaxLossHarvesting() {
        return this == TLH_SELL || this == TLH_SUB_BUY;
    }
}
