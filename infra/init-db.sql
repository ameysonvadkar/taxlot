-- Runs once, when Postgres initialises an empty data directory.
--
-- Creates only the schemas. Every table, index and constraint is created by Flyway from the
-- owning service, so that the schema history is versioned and reviewable rather than hidden
-- in a container bootstrap script.
--
-- One schema per service, mirroring the ownership boundaries in CLAUDE.md section 4:
--   market_data  securities, prices, substitutes
--   portfolio    models, accounts, exclusions, tax lots, wash-sale blocks, realised gains
--   rebalance    proposals, proposed trades

CREATE SCHEMA IF NOT EXISTS market_data;
CREATE SCHEMA IF NOT EXISTS portfolio;
CREATE SCHEMA IF NOT EXISTS rebalance;

GRANT ALL ON SCHEMA market_data TO taxlot;
GRANT ALL ON SCHEMA portfolio TO taxlot;
GRANT ALL ON SCHEMA rebalance TO taxlot;
