# 3. Idempotency key inserted first in the same transaction

Status: accepted

Context: Clients retry after timeouts, and a retry must never book the money twice, even when two identical
requests arrive at the same moment.

Decision: Each write inserts its `Idempotency-Key` with a hash of the request body as the first statement of
the transaction. A unique constraint decides the race. The same key with the same body replays the stored
response, with a different body it is a 409.

Consequences: A duplicate is detected by the database and not by application code. If the entry fails, the key
rolls back with it, so a failed request can be retried safely.
