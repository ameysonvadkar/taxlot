package com.taxlot.marketdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;

/** One investable security in the universe. */
@Entity
@Table(name = "security")
public class Security {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 10)
    private String ticker;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(nullable = false, length = 64)
    private String sector;

    protected Security() {
        // for JPA
    }

    public Security(String ticker, String name, String sector) {
        this.ticker = ticker;
        this.name = name;
        this.sector = sector;
    }

    public Long getId() {
        return id;
    }

    public String getTicker() {
        return ticker;
    }

    public String getName() {
        return name;
    }

    public String getSector() {
        return sector;
    }

    /**
     * Identity is the ticker, not the generated id.
     *
     * <p>Ticker is the real natural key — it is unique in the schema and stable across
     * environments, whereas the id differs between a seeded test container and production.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof Security security && Objects.equals(ticker, security.ticker);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ticker);
    }

    @Override
    public String toString() {
        return "Security[" + ticker + " " + sector + "]";
    }
}
