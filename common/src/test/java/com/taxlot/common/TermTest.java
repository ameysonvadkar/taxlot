package com.taxlot.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TermTest {

    /**
     * The two terms are persisted by name in {@code realized_gain.term}, so renaming a constant
     * would silently orphan existing rows. This pins the wire format.
     */
    @Test
    void namesArePartOfThePersistedContract() {
        assertThat(Term.values()).containsExactly(Term.SHORT, Term.LONG);
        assertThat(Term.SHORT.name()).isEqualTo("SHORT");
        assertThat(Term.LONG.name()).isEqualTo("LONG");
        assertThat(Term.valueOf("SHORT")).isEqualTo(Term.SHORT);
        assertThat(Term.valueOf("LONG")).isEqualTo(Term.LONG);
    }
}
