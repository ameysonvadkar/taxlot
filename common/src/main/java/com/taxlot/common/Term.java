package com.taxlot.common;

/**
 * How long a lot was held before it was sold, which decides the tax rate applied to the gain.
 *
 * <p>Held one year or less is {@link #SHORT} and taxed at the higher rate; held more than one
 * year is {@link #LONG} and taxed at the lower rate. The classification rule itself lives with
 * the realised-gain logic, not here — this enum only names the two outcomes.
 */
public enum Term {
    SHORT,
    LONG
}
