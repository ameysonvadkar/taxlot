package com.taxlot.marketdata.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A security's most recent closing price.
 *
 * <p>Carries the date as well as the price: a caller that receives a price with no date cannot
 * tell a current quote from one left over from a simulation that stopped a month ago.
 */
public record LatestPrice(String ticker, BigDecimal close, LocalDate priceDate) {}
