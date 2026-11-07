# 0008 Several currencies, balanced per currency

## Context

Customers outside the euro area pay in their own currency, brands are paid in EUR. Adding USD cents to EUR cents
would make the books look balanced while being wrong.

## Decision

Every account has one currency, and every posting must match it. An entry may contain several currencies, but the
postings of each currency must sum to 0 on their own. An exchange runs through `fx:{currency}` accounts, so the rate
is visible as two postings. The service checks this, and a deferred database trigger checks it again at commit.

## Consequences

The global check and the dashboard work per currency. Exchange gains and losses show up as the balance of the
`fx` accounts. The demo uses a fixed rate; a real system would store the rate used on the entry.
