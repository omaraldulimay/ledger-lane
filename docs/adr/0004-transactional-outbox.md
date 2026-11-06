# 4. Transactional outbox instead of dual writes

Status: accepted

Context: Every entry must be published as an event, but writing to the database and to Kafka cannot be one
atomic step. A crash in between would lose or invent events.

Decision: The entry and its `outbox` row are written in one transaction. A relay claims unpublished rows with
`FOR UPDATE SKIP LOCKED` in id order, publishes them and sets `published_at`.

Consequences: An event exists if and only if the entry exists. Delivery is at least once, because a crash
after publishing resends the row. Consumers dedupe by entry id, which is the message key.
