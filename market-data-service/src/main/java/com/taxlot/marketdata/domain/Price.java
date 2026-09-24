package com.taxlot.marketdata.domain;

import com.taxlot.common.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/** A security's closing price on one date. */
@Entity
@Table(name = "price")
public class Price {

    @EmbeddedId
    private PriceId id;

    /**
     * {@code close} is a SQL keyword in some dialects, so the column is named explicitly rather
     * than left to Hibernate's naming strategy.
     */
    @Column(name = "close", nullable = false, precision = 18, scale = 4)
    private BigDecimal close;

    protected Price() {
        // for JPA
    }

    public Price(Long securityId, LocalDate priceDate, BigDecimal close) {
        this.id = new PriceId(securityId, priceDate);
        // Normalised on the way in, so a caller passing 170 and a caller passing 170.0000
        // produce byte-identical rows.
        this.close = Money.price(close);
    }

    public PriceId getId() {
        return id;
    }

    public Long getSecurityId() {
        return id.getSecurityId();
    }

    public LocalDate getPriceDate() {
        return id.getPriceDate();
    }

    public BigDecimal getClose() {
        return close;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof Price price && Objects.equals(id, price.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Price[" + id + " = " + close + "]";
    }
}
