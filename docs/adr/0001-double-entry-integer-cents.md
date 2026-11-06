# 1. Double entry with integer cents

Status: accepted

Context: The service must explain every cent, and rounding errors in money are not acceptable.

Decision: Every journal entry has at least two postings that sum to exactly 0. Amounts are `long` minor units
(cents) with a currency. Entries and postings are immutable, enforced by database triggers. Mistakes are
fixed with a reversal entry.

Consequences: The global check "sum of all postings is 0" proves consistency at any time. There is no
floating point and no in place correction, so history stays auditable. Clients must send cents.
