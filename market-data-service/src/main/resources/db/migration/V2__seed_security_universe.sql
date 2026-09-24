-- The 30-security universe from CLAUDE.md section 8, across six sectors.
--
-- Tickers and sectors are real; prices are invented and then moved by the simulator. Nothing
-- here is market data in any meaningful sense — it exists so the rebalancing and harvesting
-- logic has a realistic shape of universe to work against.

INSERT INTO security (ticker, name, sector) VALUES
    -- Tech
    ('AAPL',  'Apple Inc.',                  'TECH'),
    ('MSFT',  'Microsoft Corporation',       'TECH'),
    ('NVDA',  'NVIDIA Corporation',          'TECH'),
    ('GOOGL', 'Alphabet Inc.',               'TECH'),
    ('META',  'Meta Platforms Inc.',         'TECH'),
    -- Financials
    ('JPM',   'JPMorgan Chase & Co.',        'FINANCIALS'),
    ('BAC',   'Bank of America Corporation', 'FINANCIALS'),
    ('GS',    'The Goldman Sachs Group Inc.','FINANCIALS'),
    ('MS',    'Morgan Stanley',              'FINANCIALS'),
    ('WFC',   'Wells Fargo & Company',       'FINANCIALS'),
    -- Healthcare
    ('JNJ',   'Johnson & Johnson',           'HEALTHCARE'),
    ('PFE',   'Pfizer Inc.',                 'HEALTHCARE'),
    ('UNH',   'UnitedHealth Group Inc.',     'HEALTHCARE'),
    ('MRK',   'Merck & Co. Inc.',            'HEALTHCARE'),
    ('ABBV',  'AbbVie Inc.',                 'HEALTHCARE'),
    -- Consumer
    ('AMZN',  'Amazon.com Inc.',             'CONSUMER'),
    ('WMT',   'Walmart Inc.',                'CONSUMER'),
    ('KO',    'The Coca-Cola Company',       'CONSUMER'),
    ('PEP',   'PepsiCo Inc.',                'CONSUMER'),
    ('MCD',   'McDonald''s Corporation',     'CONSUMER'),
    -- Energy
    ('XOM',   'Exxon Mobil Corporation',     'ENERGY'),
    ('CVX',   'Chevron Corporation',         'ENERGY'),
    ('COP',   'ConocoPhillips',              'ENERGY'),
    ('SLB',   'Schlumberger N.V.',           'ENERGY'),
    ('EOG',   'EOG Resources Inc.',          'ENERGY'),
    -- Industrials
    ('CAT',   'Caterpillar Inc.',            'INDUSTRIALS'),
    ('DE',    'Deere & Company',             'INDUSTRIALS'),
    ('HON',   'Honeywell International Inc.','INDUSTRIALS'),
    ('GE',    'General Electric Company',    'INDUSTRIALS'),
    ('UPS',   'United Parcel Service Inc.',  'INDUSTRIALS');


-- Rank-1 substitutes: the explicit pairs named in CLAUDE.md, in both directions.
--
-- GOOGL is the one asymmetry. CLAUDE.md pairs it with both NVDA and META, but a security can
-- only have one rank-1 substitute (uq_substitute_rank), so GOOGL->NVDA takes rank 1 and
-- GOOGL->META takes rank 2. The reverse directions are both rank 1, since NVDA and META each
-- name GOOGL as their only listed partner.
INSERT INTO substitute (security_id, substitute_id, rank)
SELECT a.id, b.id, p.rank
FROM (VALUES
    -- Tech
    ('AAPL',  'MSFT',  1), ('MSFT',  'AAPL',  1),
    ('NVDA',  'GOOGL', 1), ('GOOGL', 'NVDA',  1),
    ('META',  'GOOGL', 1), ('GOOGL', 'META',  2),
    -- Financials
    ('JPM',   'BAC',   1), ('BAC',   'JPM',   1),
    ('GS',    'MS',    1), ('MS',    'GS',    1),
    -- Healthcare
    ('JNJ',   'PFE',   1), ('PFE',   'JNJ',   1),
    ('MRK',   'ABBV',  1), ('ABBV',  'MRK',   1),
    -- Consumer
    ('KO',    'PEP',   1), ('PEP',   'KO',    1),
    ('WMT',   'AMZN',  1), ('AMZN',  'WMT',   1),
    -- Energy
    ('XOM',   'CVX',   1), ('CVX',   'XOM',   1),
    -- Industrials
    ('CAT',   'DE',    1), ('DE',    'CAT',   1)
) AS p(from_ticker, to_ticker, rank)
JOIN security a ON a.ticker = p.from_ticker
JOIN security b ON b.ticker = p.to_ticker;


-- Same-sector fallbacks, filling every remaining pair.
--
-- CLAUDE.md names only 11 pairs, which would leave 9 of the 30 securities (WFC, UNH, MCD, COP,
-- SLB, EOG, HON, GE, UPS) with no substitute at all — and a security with no substitute can
-- never be tax-loss harvested, because selling it would drop the client's market exposure.
-- Ranking every same-sector peer behind the explicit pairs keeps harvesting available across
-- the whole universe while preserving CLAUDE.md's pairs as first choice.
--
-- Same-sector is the right fallback specifically because it is NOT "substantially identical"
-- under the wash-sale rule: a different company in the same sector preserves the risk exposure
-- without disallowing the loss.
INSERT INTO substitute (security_id, substitute_id, rank)
SELECT candidate.security_id,
       candidate.substitute_id,
       candidate.existing_max
           + ROW_NUMBER() OVER (PARTITION BY candidate.security_id ORDER BY candidate.ticker)
FROM (
    SELECT s.id   AS security_id,
           peer.id AS substitute_id,
           peer.ticker,
           COALESCE((SELECT MAX(x.rank) FROM substitute x WHERE x.security_id = s.id), 0) AS existing_max
    FROM security s
    JOIN security peer ON peer.sector = s.sector AND peer.id <> s.id
    WHERE NOT EXISTS (
        SELECT 1 FROM substitute x
        WHERE x.security_id = s.id AND x.substitute_id = peer.id
    )
) AS candidate;


-- Opening prices, on a fixed anchor date. The simulator walks forward from the latest date
-- present, so this row set is the origin of all price history.
--
-- AAPL opens at 200.00 deliberately: that is the entry price in the CLAUDE.md worked example,
-- so the harvesting scenario can be reproduced end-to-end against seeded data.
INSERT INTO price (security_id, price_date, close)
SELECT s.id, DATE '2026-01-02', p.close
FROM (VALUES
    ('AAPL', 200.0000), ('MSFT', 420.0000), ('NVDA', 135.0000), ('GOOGL', 175.0000), ('META', 580.0000),
    ('JPM',  240.0000), ('BAC',   45.0000), ('GS',   520.0000), ('MS',    125.0000), ('WFC',   72.0000),
    ('JNJ',  155.0000), ('PFE',   26.0000), ('UNH',  520.0000), ('MRK',   100.0000), ('ABBV', 190.0000),
    ('AMZN', 220.0000), ('WMT',   92.0000), ('KO',    63.0000), ('PEP',   155.0000), ('MCD',  295.0000),
    ('XOM',  115.0000), ('CVX',  160.0000), ('COP',  105.0000), ('SLB',    42.0000), ('EOG',  125.0000),
    ('CAT',  390.0000), ('DE',   430.0000), ('HON',  215.0000), ('GE',    195.0000), ('UPS',  130.0000)
) AS p(ticker, close)
JOIN security s ON s.ticker = p.ticker;
