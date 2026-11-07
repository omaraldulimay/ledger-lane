# 0007 API keys with roles

## Context

The ledger moves money. Anyone who can reach the API could post transfers or pay out brands. The callers are
machines (the shop backend, the finance tool) and a read only control room, not people with passwords.

## Decision

Spring Security with a stateless API key filter. The key travels in the `X-Api-Key` header. Configuration stores
only the SHA-256 hash, and the comparison runs in constant time. Three roles: SHOP posts transfers and reversals,
FINANCE also pays out, reconciles, creates accounts and reads, AUDITOR only reads. Errors are problem+json (401, 403).
CSRF protection is off because there are no cookies or sessions.

## Consequences

Simple to operate and to test. Keys are rotated by adding the new hash, switching the client, removing the old hash.
OAuth2 with a token server would add user identity and expiry; it is the next step if people, not services, call the API.
