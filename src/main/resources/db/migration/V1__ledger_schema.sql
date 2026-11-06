CREATE TABLE accounts (
    id            text PRIMARY KEY CHECK (id <> ''),
    type          text    NOT NULL CHECK (type IN ('asset', 'liability', 'revenue', 'expense', 'equity')),
    currency      char(3) NOT NULL,
    normal_side   text    NOT NULL CHECK (normal_side IN ('debit', 'credit')),
    allow_negative boolean NOT NULL DEFAULT false,
    balance       bigint  NOT NULL DEFAULT 0,
    version       bigint  NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE journal_entries (
    id                 uuid PRIMARY KEY,
    description        text NOT NULL,
    reverses_entry_id  uuid UNIQUE REFERENCES journal_entries (id),
    created_at         timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE postings (
    id          bigserial PRIMARY KEY,
    entry_id    uuid    NOT NULL REFERENCES journal_entries (id),
    account_id  text    NOT NULL REFERENCES accounts (id),
    amount      bigint  NOT NULL CHECK (amount <> 0),
    currency    char(3) NOT NULL
);
CREATE INDEX postings_entry_idx ON postings (entry_id);
CREATE INDEX postings_account_idx ON postings (account_id, id DESC);

-- The key row is written first in the request transaction; the entry row follows,
-- so the reference is checked at commit.
CREATE TABLE idempotency_keys (
    key           text PRIMARY KEY,
    request_hash  text NOT NULL,
    entry_id      uuid NOT NULL REFERENCES journal_entries (id) DEFERRABLE INITIALLY DEFERRED,
    response      jsonb,
    created_at    timestamptz NOT NULL DEFAULT now()
);

-- Entries and postings are immutable: corrections are reversal entries.
CREATE FUNCTION reject_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% on % is not allowed, entries are immutable', TG_OP, TG_TABLE_NAME;
END $$;

CREATE TRIGGER journal_entries_immutable BEFORE UPDATE OR DELETE ON journal_entries
    FOR EACH ROW EXECUTE FUNCTION reject_change();
CREATE TRIGGER postings_immutable BEFORE UPDATE OR DELETE ON postings
    FOR EACH ROW EXECUTE FUNCTION reject_change();

-- Double entry at commit time: at least two postings that sum to zero.
CREATE FUNCTION check_entry_balanced() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    total bigint;
    n     integer;
BEGIN
    SELECT COALESCE(SUM(amount), 0), COUNT(*) INTO total, n FROM postings WHERE entry_id = NEW.entry_id;
    IF n < 2 OR total <> 0 THEN
        RAISE EXCEPTION 'entry % is not balanced (% postings, sum %)', NEW.entry_id, n, total;
    END IF;
    RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER postings_balanced AFTER INSERT ON postings
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_entry_balanced();
