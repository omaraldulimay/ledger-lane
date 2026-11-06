-- Optional business reference (order id, payout id) used to match entries against a bank statement.
ALTER TABLE journal_entries ADD COLUMN reference text;
CREATE INDEX journal_entries_reference_idx ON journal_entries (reference) WHERE reference IS NOT NULL;
