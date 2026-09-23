/**
 * Pure domain logic for rebalancing and tax-loss harvesting.
 *
 * <p>Nothing in this package or its subpackages may depend on Spring, a database, an HTTP client,
 * the filesystem, or the system clock. Everything the engine needs — lots, prices, model weights,
 * exclusions, wash-sale blocks, and "today" — is passed in as an immutable argument and every
 * result is returned as an immutable value.
 *
 * <p>The reason is testability. The rules encoded here (HIFO lot selection, the 30-day wash-sale
 * window, drift thresholds) are the part of TaxLot that must be provably correct, and a pure
 * function can be driven through thousands of generated cases in milliseconds with no container
 * to start and no fixture to reset. It also means a failing test points at a rule, not at wiring.
 *
 * <p>Populated in Phase 3: {@code LotSelector}, {@code DriftCalculator}, {@code WashSaleChecker},
 * {@code TlhPlanner}, {@code SubstituteSelector}, {@code ProposalValidator}.
 */
package com.taxlot.engine;
