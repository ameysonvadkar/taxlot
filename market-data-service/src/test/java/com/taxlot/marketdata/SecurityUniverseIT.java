package com.taxlot.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import com.taxlot.marketdata.domain.Security;
import com.taxlot.marketdata.domain.Substitute;
import com.taxlot.marketdata.repository.SecurityRepository;
import com.taxlot.marketdata.repository.SubstituteRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Checks the Flyway-seeded universe against CLAUDE.md section 8.
 *
 * <p>Seed data is easy to get subtly wrong — a typo'd ticker, a substitute pointing the wrong
 * way, a sector spelled two different ways — and every one of those failures shows up much later
 * as a confusing rebalance result rather than as an obvious error.
 */
@SpringBootTest
class SecurityUniverseIT extends AbstractIntegrationTest {

    @Autowired
    private SecurityRepository securities;

    @Autowired
    private SubstituteRepository substitutes;

    @Test
    @DisplayName("30 securities across 6 sectors, 5 in each")
    void theUniverseMatchesTheSpecification() {
        List<Security> all = securities.findAll();

        assertThat(all).hasSize(30);
        assertThat(all).extracting(Security::getSector)
                .containsOnly("TECH", "FINANCIALS", "HEALTHCARE", "CONSUMER", "ENERGY", "INDUSTRIALS");

        assertThat(all)
                .filteredOn(security -> "TECH".equals(security.getSector()))
                .extracting(Security::getTicker)
                .containsExactlyInAnyOrder("AAPL", "MSFT", "NVDA", "GOOGL", "META");

        assertThat(all)
                .filteredOn(security -> "ENERGY".equals(security.getSector()))
                .extracting(Security::getTicker)
                .containsExactlyInAnyOrder("XOM", "CVX", "COP", "SLB", "EOG");
    }

    @Test
    void everySectorHasExactlyFiveSecurities() {
        for (String sector : List.of("TECH", "FINANCIALS", "HEALTHCARE", "CONSUMER", "ENERGY", "INDUSTRIALS")) {
            assertThat(securities.findBySectorOrderByTicker(sector))
                    .as("sector %s", sector)
                    .hasSize(5);
        }
    }

    @Test
    @DisplayName("the worked example's pair is seeded: AAPL's first substitute is MSFT")
    void applesPreferredSubstituteIsMicrosoft() {
        assertThat(substitutes.findSubstituteTickersFor("AAPL")).first().isEqualTo("MSFT");
        assertThat(substitutes.findSubstituteTickersFor("MSFT")).first().isEqualTo("AAPL");
    }

    @Test
    @DisplayName("every explicit pair from CLAUDE.md is ranked first, in both directions")
    void theExplicitPairsAreRankedFirst() {
        assertFirstSubstitute("JPM", "BAC");
        assertFirstSubstitute("BAC", "JPM");
        assertFirstSubstitute("GS", "MS");
        assertFirstSubstitute("MS", "GS");
        assertFirstSubstitute("JNJ", "PFE");
        assertFirstSubstitute("PFE", "JNJ");
        assertFirstSubstitute("MRK", "ABBV");
        assertFirstSubstitute("ABBV", "MRK");
        assertFirstSubstitute("KO", "PEP");
        assertFirstSubstitute("PEP", "KO");
        assertFirstSubstitute("WMT", "AMZN");
        assertFirstSubstitute("AMZN", "WMT");
        assertFirstSubstitute("XOM", "CVX");
        assertFirstSubstitute("CVX", "XOM");
        assertFirstSubstitute("CAT", "DE");
        assertFirstSubstitute("DE", "CAT");
        assertFirstSubstitute("NVDA", "GOOGL");
        assertFirstSubstitute("META", "GOOGL");
    }

    /**
     * GOOGL is the one security CLAUDE.md pairs with two others. Only one can be rank 1, so the
     * order is pinned here rather than left to whatever the insert happened to produce.
     */
    @Test
    void googleKeepsBothOfItsNamedPairsInRankOrder() {
        assertThat(substitutes.findSubstituteTickersFor("GOOGL")).startsWith("NVDA", "META");
    }

    @Test
    @DisplayName("every security is harvestable: all 30 have at least one substitute")
    void noSecurityIsLeftWithoutASubstitute() {
        for (Security security : securities.findAll()) {
            assertThat(substitutes.findSubstituteTickersFor(security.getTicker()))
                    .as("substitutes for %s", security.getTicker())
                    .isNotEmpty();
        }
    }

    @Test
    @DisplayName("a substitute is always a different company in the same sector")
    void substitutesAreSameSectorAndNeverSelf() {
        for (Security security : securities.findAll()) {
            List<String> replacements = substitutes.findSubstituteTickersFor(security.getTicker());

            assertThat(replacements)
                    .as("%s must not substitute for itself — that is the wash sale", security.getTicker())
                    .doesNotContain(security.getTicker());

            for (String replacement : replacements) {
                Security target = securities.findByTicker(replacement).orElseThrow();
                assertThat(target.getSector())
                        .as("%s -> %s must stay within a sector to preserve exposure",
                                security.getTicker(), replacement)
                        .isEqualTo(security.getSector());
            }
        }
    }

    @Test
    @DisplayName("ranks are unique per security, so 'the best substitute' is deterministic")
    void ranksAreUniquePerSecurity() {
        for (Security security : securities.findAll()) {
            List<Substitute> rows = substitutes.findByIdSecurityIdOrderByRank(security.getId());
            Set<Short> ranks = new HashSet<>();
            for (Substitute row : rows) {
                assertThat(ranks.add(row.getRank()))
                        .as("%s has a duplicate rank %s", security.getTicker(), row.getRank())
                        .isTrue();
            }
        }
    }

    private void assertFirstSubstitute(String ticker, String expected) {
        assertThat(substitutes.findSubstituteTickersFor(ticker))
                .as("first substitute for %s", ticker)
                .first()
                .isEqualTo(expected);
    }
}
