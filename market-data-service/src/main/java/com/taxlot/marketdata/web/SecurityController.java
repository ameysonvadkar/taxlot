package com.taxlot.marketdata.web;

import com.taxlot.marketdata.domain.Security;
import com.taxlot.marketdata.service.MarketDataService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/securities")
@Tag(name = "Securities", description = "The investable universe and the substitute graph")
public class SecurityController {

    private final MarketDataService marketData;

    public SecurityController(MarketDataService marketData) {
        this.marketData = marketData;
    }

    @GetMapping
    @Operation(summary = "List the security universe, optionally filtered by sector")
    public List<SecurityResponse> list(@RequestParam(required = false) String sector) {
        List<Security> found = sector == null
                ? marketData.findAllSecurities()
                : marketData.findSecuritiesInSector(sector.toUpperCase());
        return found.stream().map(SecurityResponse::from).toList();
    }

    @GetMapping("/{ticker}/substitutes")
    @Operation(summary = "Substitutes for a security, best first, for tax-loss harvesting")
    public List<String> substitutes(@PathVariable String ticker) {
        return marketData.findSubstitutesFor(ticker.toUpperCase());
    }
}
