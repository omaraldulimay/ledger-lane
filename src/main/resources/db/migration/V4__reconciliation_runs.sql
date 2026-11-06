-- Every uploaded bank statement produces one run; the dashboard shows the latest.
CREATE TABLE reconciliation_runs (
    id         bigserial PRIMARY KEY,
    created_at timestamptz NOT NULL DEFAULT now(),
    result     jsonb NOT NULL
);
