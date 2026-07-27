# cloud-itonami-crowdfunding-fulfillment

**FulfillmentAdvisor ⊣ FulfillmentGovernor** — the actor that writes the
records the payout actor releases money against, and holds no addresses
while doing it.

A `cloud-itonami` blueprint actor in the workspace's standard shape:
advisor sealed into one node of a [langgraph-clj](https://github.com/kotoba-lang/langgraph)
StateGraph, an independent governor, a 0→3 phase gate, an append-only
audit ledger. Domain rules from
[`kotoba-lang/crowdfunding`](https://github.com/kotoba-lang/crowdfunding);
design record: ADR-2607268500.

## Why this actor matters more than it looks

Everything before it happens in weeks. This happens over months or years,
and it is where rewards crowdfunding actually fails — not usually in
fraud, but in a creator who goes quiet while backers cannot tell whether
the project is late or dead.

Mechanically, it is also the actor
[`cloud-itonami-crowdfunding-payout`](https://github.com/cloud-itonami/cloud-itonami-crowdfunding-payout)
depends on: that actor releases creator money against
`:on-first-shipment` and `:on-fulfillment-complete`, and those are facts
about the records written **here**. A line marked shipped in this repo
unlocks money in that one.

Which is exactly why `:shipped` is refused without a tracking reference
the backer can check. Remove that check and `:ship-line` becomes a claim
a creator can make about themselves that pays them.

## Two permanent scope exclusions

Not policies that a later phase could relax:

1. **`:refunded` is unreachable.** It is a real fulfilment state, but
   reaching it is a *money* act and money belongs to the payout actor. An
   attempt is reported as `:refund-out-of-scope`, deliberately distinct
   from `:illegal-transition` — "not allowed right now" and "never
   allowed here" are different facts for someone reading the ledger.
2. **No personal data enters the record.** A survey response carries
   `:address-ref` and `:contact-ref` — opaque references resolved by a
   system that is allowed to hold personal data. A proposal carrying an
   actual postcode, phone number or name is refused, and the check walks
   the whole value tree rather than a fixed field list: the failure mode
   is someone adding a convenience field, not someone deliberately
   putting an address in `:address-ref`.

This record ends up in logs, ledgers, checkpoints and test fixtures. A
value that reaches one reaches all of them.

## The invariant

> The governor rejects; the actor never writes what it refuses.

Seven HARD checks, un-overridable by any human approval:

| Check | Why |
|---|---|
| Campaign not fulfilling | nothing to fulfil before the campaign got there |
| Illegal transition | delegated to `fulfillment/transitions` — one table, not two that drift |
| Refund attempted | permanent scope exclusion (above) |
| Untracked shipment | it unlocks a payout gate; the backer must be able to verify it |
| Personal data | a value where a reference belongs |
| `:effect` ≠ `:propose` | a claim to actuate outside governance |
| Scope exclusion | claiming to have refunded, charged or paid out |

`:disclose-failure` and `:flag-fulfillment-concern` **always escalate**,
in every phase including 3. A failure disclosure is a named person saying
they cannot deliver, with a remedy from a closed set — "we're working on
it" is an update, not a disclosure. No actor makes that statement on a
creator's behalf; the approver signs off on *recording* it, and
`:disclosure/stated-by` remains the creator.

## Late and silent are separate facts

`store/accountability` reports `:accountability/overdue?` (past the
estimated delivery date) and `:accountability/stale?` (no update since
`stale-before`) independently, and only flags
`:accountability/owes-disclosure?` when **both** hold. Late while posting
is ordinary; a single post ends the ambiguity. And `stale?` is `nil` — not
`false` — when the caller supplied no `stale-before`: "we did not measure"
and "they are not stale" are different statements, and only one of them is
evidence.

Nothing here concludes that a creator failed. `:accountability/adjudicated?`
is `false` on every result.

## Run it

```bash
clojure -M:dev:run     # offline demo: survey → ship → gates, plus each refusal
clojure -M:dev:test    # 24 tests
clojure -M:lint
```

## Licence

AGPL-3.0-or-later.
