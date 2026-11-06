-- Transactional outbox: written in the same transaction as the journal entry, published by the relay.
CREATE TABLE outbox (
    id           bigserial PRIMARY KEY,
    entry_id     uuid        NOT NULL REFERENCES journal_entries (id),
    topic        text        NOT NULL,
    payload      jsonb       NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz
);
-- The relay only ever looks at unpublished rows.
CREATE INDEX outbox_unpublished_idx ON outbox (id) WHERE published_at IS NULL;

-- Entries written before the outbox existed get a row too, so every entry is published once.
INSERT INTO outbox (entry_id, topic, payload, created_at)
SELECT e.id, 'ledger.entries',
       jsonb_build_object('id', e.id, 'description', e.description, 'reversesEntryId', e.reverses_entry_id,
                          'createdAt', e.created_at,
                          'postings', (SELECT jsonb_agg(jsonb_build_object('accountId', p.account_id,
                                       'amount', p.amount, 'currency', p.currency) ORDER BY p.id)
                                       FROM postings p WHERE p.entry_id = e.id)),
       e.created_at
FROM journal_entries e ORDER BY e.created_at, e.id;
