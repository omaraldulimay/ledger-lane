# 5. Plain SQL with JdbcClient instead of JPA

Status: accepted

Context: The core of the service is locking order, constraints, triggers and set based checks. An ORM hides
exactly these details.

Decision: Use Spring `JdbcClient` with explicit SQL and Flyway migrations. No JPA, no entity cache.

Consequences: Every query and lock is visible in the code and reviewable. There is more boilerplate for
mapping rows, and no automatic schema generation, which is acceptable for a small schema.
