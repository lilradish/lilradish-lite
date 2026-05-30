<!--
ADR template. Copy to docs/adr/NNNN-kebab-title.md, fill in, delete this comment.
Only write an ADR when the decision (a) reshapes structure, (b) trades off a
quality attribute, or (c) is expensive to reverse. Otherwise skip it.
-->
---
status: Accepted     # Proposed | Accepted | Deprecated | Superseded by NNNN | Rejected
                     # a live bet starts at Proposed, then flips to Accepted on commit
date: YYYY-MM-DD
---

# NNNN. <Name the decision, not the problem>

> **Decision.** In the context of \<use case>, facing \<constraint>,
> we chose \<option>, neglecting \<alternatives>,
> to achieve \<benefit>, accepting \<trade-off>.

<!-- One sentence, all six slots filled. `accepting <trade-off>` is mandatory:
a decision with no cost is a sales pitch. This line is the whole summary. -->

## Context

<!-- 2-3 sentences: the forces that compel a decision. Problem only, no solution. -->

## Options

<!-- Real alternatives only, no straw men. One line each on why it won/lost. -->

- **\<chosen>** ✅ — \<why — name the deciding criterion, e.g. "simplest ops, no agent">
- \<option B> — \<why not>

## Consequences

- 👍 \<positive outcomes>
- 👎 \<negative outcomes, debt, cost>   <!-- MANDATORY, must not be empty -->

## Links

<!-- Optional. Only artifacts in this repo: other ADRs, docs, code paths. -->

- Related: \<ADR-NNNN, `docs/architecture.md#section`, `path/to/code`>
