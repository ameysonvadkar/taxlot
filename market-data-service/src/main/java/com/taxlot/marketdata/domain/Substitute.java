package com.taxlot.marketdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.Objects;

/**
 * "If we sell this security at a loss, what do we buy instead?"
 *
 * <p>Ranked, because the first choice may itself be excluded by the client's mandate or already
 * wash-sale blocked, and the engine then falls through to the next.
 */
@Entity
@Table(name = "substitute")
public class Substitute {

    @EmbeddedId
    private SubstituteId id;

    /** 1 is the preferred replacement. Unique per security, enforced by the schema. */
    @Column(name = "rank", nullable = false)
    private Short rank;

    protected Substitute() {
        // for JPA
    }

    public Substitute(Long securityId, Long substituteId, short rank) {
        this.id = new SubstituteId(securityId, substituteId);
        this.rank = rank;
    }

    public SubstituteId getId() {
        return id;
    }

    public Long getSecurityId() {
        return id.getSecurityId();
    }

    public Long getSubstituteId() {
        return id.getSubstituteId();
    }

    public Short getRank() {
        return rank;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof Substitute that && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Substitute[" + id + " rank " + rank + "]";
    }
}
