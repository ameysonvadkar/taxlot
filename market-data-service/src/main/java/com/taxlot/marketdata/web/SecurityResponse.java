package com.taxlot.marketdata.web;

import com.taxlot.marketdata.domain.Security;

/** A security as returned over HTTP. */
public record SecurityResponse(Long id, String ticker, String name, String sector) {

    public static SecurityResponse from(Security security) {
        return new SecurityResponse(
                security.getId(), security.getTicker(), security.getName(), security.getSector());
    }
}
