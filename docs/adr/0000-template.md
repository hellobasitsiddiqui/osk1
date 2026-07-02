<!--
  ADR template for OpenSkeleton (osk1).

  Every architecturally significant decision gets its own numbered file in this
  directory (0001-, 0002-, …), copied from this template. The goal is a durable,
  greppable record of WHY a choice was made, so a maintainer who did not attend
  the discussion can reconstruct the reasoning — and knows what to revisit if the
  forces that drove the decision change.

  How to use:
    1. Copy this file to `NNNN-short-title.md` (next free number, kebab-case).
    2. Fill in every section below; keep it short (one screen is ideal).
    3. Set Status to Proposed, then Accepted once merged. Never delete a
       superseded ADR — mark it Superseded and link the replacement, so the
       history stays visible (tombstone, don't silently delete).
-->

# NNNN. Short title of the decision

- **Status:** Proposed | Accepted | Superseded by [ADR-XXXX](XXXX-title.md)
- **Date:** YYYY-MM-DD

## Context

What is the situation that forces a decision? Describe the problem, the
constraints (technical, cost, team, time), and the forces in tension. State
facts and requirements, not the choice itself — a reader should understand the
pressure before seeing the answer.

## Decision

The choice we are making, stated in one or two clear sentences ("We will …").
Follow with the key reasons it beats the alternatives considered. List the
alternatives explicitly so a future reader knows they were weighed, not missed.

## Consequences

What becomes easier and what becomes harder as a result — the trade-off we are
accepting, including new obligations, risks, and follow-up work this decision
creates. Be honest about the downsides; that is what makes an ADR useful later.
