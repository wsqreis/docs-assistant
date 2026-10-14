-- How many chunks contain each lexeme (refreshed after every ingestion from ts_stat).
-- Keyword search ignores lexemes that are too common in this corpus to discriminate, like "spring" or "use".
CREATE TABLE lexeme_stats (
    word TEXT PRIMARY KEY,
    ndoc INT  NOT NULL
);
