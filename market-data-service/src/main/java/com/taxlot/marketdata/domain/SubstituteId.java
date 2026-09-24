package com.taxlot.marketdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

/** Composite key for {@link Substitute}: one row per (security, substitute) pair. */
@Embeddable
public class SubstituteId implements Serializable {

    @Column(name = "security_id", nullable = false)
    private Long securityId;

    @Column(name = "substitute_id", nullable = false)
    private Long substituteId;

    protected SubstituteId() {
        // for JPA
    }

    public SubstituteId(Long securityId, Long substituteId) {
        this.securityId = Objects.requireNonNull(securityId, "securityId must not be null");
        this.substituteId = Objects.requireNonNull(substituteId, "substituteId must not be null");
    }

    public Long getSecurityId() {
        return securityId;
    }

    public Long getSubstituteId() {
        return substituteId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof SubstituteId that
                && Objects.equals(securityId, that.securityId)
                && Objects.equals(substituteId, that.substituteId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(securityId, substituteId);
    }

    @Override
    public String toString() {
        return "SubstituteId[" + securityId + " -> " + substituteId + "]";
    }
}
