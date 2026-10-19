# Retained legacy contracts

Captured on 2026-10-19 before removing the Next.js application. This is a behavior and fixture archive for later Clojure work. It does not implement auth, imports, Ozon fetching, or an inventory migration.

- [Identity, workspaces and email](identity-and-email.md): security and recovery behavior, including uncommitted timeout fixes.
- [Ozon fetching](ozon-fetching.md): source API, decoding, retries, progress and credential format.
- [Import integrity](imports.md): complete source lines, review and provenance.
- [SQL test retention](sql-tests.md): exact standalone harness keep list and its limits.
- [Historical Auth configuration](runbooks/auth-configuration-legacy.md) and [Ozon operations](runbooks/ozon-operations-legacy.md): copied runbook excerpts.
- [Source manifest](sources.json): immutable baseline, source line ranges and hashes of the captured working files.
- [Fixtures](fixtures/README.md): small synthetic cases extracted from local tests.

## Source references

`baseline:path:line` means the file at commit `9f799586a62226bc62643e5dedc54d76a0176359`, not a path in the replacement app. Read it with `git show 9f799586a62226bc62643e5dedc54d76a0176359:path`. Baseline source files and old test commands may have been removed from the current checkout.

`working:path:line` means the pre-removal working file captured by SHA-256 in `sources.json`. The manifest marks changed files. Those uncommitted behavior changes are summarized explicitly in `identity-and-email.md`; they are absent from the baseline commit.

Relative Markdown links inside this tree point to retained files. Old paths inside the copied runbooks belong to the baseline only.

## Scope

Only existing users, workspaces, memberships, roles and invitation behavior carry forward now. Old inventory, candidate-to-operation rules, report calculations and old UI routes are historical evidence. The current application specifications remain the authority for the new domain.

Ozon fetcher work is deferred. The old documentation last verified Ozon contracts on 2026-07-27; extraction today did not revalidate the provider API. Fixtures are synthetic and do not prove current provider compatibility or production readiness. No production data, secrets, browser tokens or original uploaded documents are included.
