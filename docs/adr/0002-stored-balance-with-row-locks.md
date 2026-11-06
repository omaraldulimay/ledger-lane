# 2. Stored balance with row locks in id order

Status: accepted

Context: Reading a balance by summing all postings gets slower with every entry, and overdraft checks need a
consistent value while many transfers run in parallel.

Decision: The balance is stored on the account row and updated in the same transaction as the postings.
Rows are locked with `SELECT ... FOR UPDATE` in ascending account id order.

Consequences: Reads are cheap and concurrent entries never deadlock. The stored balance can drift in theory,
so the dashboard and the tests recompute it from the postings and compare.
