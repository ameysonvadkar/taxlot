package com.taxlot.marketdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Composite key for {@link Price}: one closing price per security per date.
 *
 * <p>Modelling this as a real composite key rather than adding a surrogate id means a second
 * simulator run over the same date fails on the primary key instead of quietly inserting a
 * duplicate day, which would distort every subsequent return calculation.
 */
@Embeddable
public class PriceId implements Serializable {

    @Column(name = "security_id", nullable = false)
    private Long securityId;

    @Column(name = "price_date", nullable = false)
    private LocalDate priceDate;

    protected PriceId() {
        // for JPA
    }

    public PriceId(Long securityId, LocalDate priceDate) {
        this.securityId = Objects.requireNonNull(securityId, "securityId must not be null");
        this.priceDate = Objects.requireNonNull(priceDate, "priceDate must not be null");
    }

    public Long getSecurityId() {
        return securityId;
    }

    public LocalDate getPriceDate() {
        return priceDate;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof PriceId priceId
                && Objects.equals(securityId, priceId.securityId)
                && Objects.equals(priceDate, priceId.priceDate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(securityId, priceDate);
    }

    @Override
    public String toString() {
        return "PriceId[" + securityId + " @ " + priceDate + "]";
    }
}
