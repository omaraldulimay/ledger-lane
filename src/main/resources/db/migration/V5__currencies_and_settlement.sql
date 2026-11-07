-- Multi currency: an entry may contain postings in several currencies (a currency exchange through fx accounts),
-- but the postings of each currency must sum to zero on their own.
CREATE OR REPLACE FUNCTION check_entry_balanced() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    n         integer;
    unbalanced text;
BEGIN
    SELECT COUNT(*) INTO n FROM postings WHERE entry_id = NEW.entry_id;
    SELECT string_agg(currency || ' ' || total, ', ') INTO unbalanced
    FROM (SELECT currency, SUM(amount) AS total FROM postings WHERE entry_id = NEW.entry_id
          GROUP BY currency HAVING SUM(amount) <> 0) t;
    IF n < 2 OR unbalanced IS NOT NULL THEN
        RAISE EXCEPTION 'entry % is not balanced (% postings, off by %)', NEW.entry_id, n, COALESCE(unbalanced, '0');
    END IF;
    RETURN NULL;
END $$;

-- A posting must be in the currency of its account.
CREATE FUNCTION check_posting_currency() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.currency <> (SELECT currency FROM accounts WHERE id = NEW.account_id) THEN
        RAISE EXCEPTION 'posting in % on account % of another currency', NEW.currency, NEW.account_id;
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER postings_currency BEFORE INSERT ON postings
    FOR EACH ROW EXECUTE FUNCTION check_posting_currency();

-- Payout settlement: when the bank statement shows a payout, the money leaves payouts:in-transit and cash:bank.
-- The unique column makes sure a payout is settled at most once.
ALTER TABLE journal_entries ADD COLUMN settles_entry_id uuid UNIQUE REFERENCES journal_entries (id);
