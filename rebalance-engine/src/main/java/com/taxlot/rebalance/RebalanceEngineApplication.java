package com.taxlot.rebalance;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Turns an account snapshot into a set of proposed trades, then executes them once approved.
 *
 * <p>The decision-making itself lives in {@code engine-core} as pure functions. This service is
 * the shell around it: it gathers inputs from the other two services, holds a per-account lock
 * while it works, persists the proposal with the snapshot it was computed from, and applies the
 * fills on approval.
 *
 * <p>Populated in Phase 3c.
 */
@SpringBootApplication
public class RebalanceEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(RebalanceEngineApplication.class, args);
    }
}
