# Marketplace (Ozon) Roadmap

Index for six plans. Each yields working software on its own and starts only after the previous one is merged.

| Plan | Board | Delivers | Starts when |
|---|---|---|---|
| [1 Connect and sync](2026-11-25-marketplace-1-sync.md) | 40, 41, part of 12 | Branch merged; Ozon data downloaded under the current contracts | Task 1: now. Tasks 2–3: now (40 is `planned`). Tasks 4–8: a person plans 41 slices 3–6 |
| [2 Matching](2026-11-25-marketplace-2-matching.md) | 42, 43 | Listings and warehouses matched in tables | 42 and 43 slices `planned` |
| [3 Opening amendment and cutover](2026-11-25-marketplace-3-cutover.md) | 14 (propose, accept, read), 17, 44 | Wrong openings fixable; cutover baselines | those slices `planned` |
| [4 Operations](2026-11-25-marketplace-4-operations.md) | 45 slices 1–6, a | Every operation listed with a next step; posting Sales accepted automatically; members confirm every return | those slices `planned`; release also needs the live check |
| [5 Corrections and moves](2026-11-25-marketplace-5-corrections-moves.md) | 45 slices 7–9, b, c | Ozon changes proposed as amendments; warehouse moves detected | those slices `planned` |
| [6 Transit](2026-11-25-marketplace-6-transit.md) | 46, `Stock Item Archived` | Shipments travelling and returning | those slices `planned` |

Chapter 47 stays deferred. Nothing writes to Ozon.

## Rules binding every task

- **Gate.** Before a task: `list_changelog_events` since `docs/board-sync.edn`, re-read that slice, stop unless it is `planned`. Set `in-progress`, then `ready` when its examples pass. Never set `planned`, `reviewed`, `deployed`. A contract that cannot be built goes on the board as an element comment; the slice becomes `blocked`.
- **Spec.** The board slice and its element details are the spec; this plan never overrides them. Every slice example becomes one `deftest` named after it. The tests printed in the plans are the ones whose values or placement are not obvious; the rest of the slice's examples are written in the same step.
- **Names** follow the board: `External Product Mapped` is `::external-product-mapped`.
- **REPL first.** `clj-nrepl-eval --discover-ports` from the active worktree, else `clojure -M:dev:nrepl`. Apply edits with `(user/start!)`. Never `:reload-all`. Delimiters: `clj-paren-repair <file>`.
- **Task end.** `clojure -M:dev:test`, `clojure -M:fmt check`, `clj-kondo --lint src dev .clj-kondo/hooks/datastar.clj` all clean, then commit.
- **Bel.** Malli `:map` open. A `defaction` that runs a command returns a descriptor. Post-commit work goes in `:fx`. Components take `[props]`.
- **Tables.** Each matching, cutover and operations page is one editable table. No per-row forms, no confirmation step the board does not name.
- **Storage.** Marketplace models live in `:catalogue`. The RDF adapter stores scalars only: ids and instants are text (`connections/At`), lists and maps are JSON text. No SQL migration and no change to `resources/inventory` or `resources/integrations`.
- **Secrets and people.** API keys, ciphertext, payloads and buyer data never reach a command, event, receipt, log, read model or browser. `/v1/seller/info` `legal_info` is never requested.
- **Actor.** The connector acts as the connection's UUID (`sync/connector-actor`) and only for its own connection. Every connector command checks `(= actor (sync/connector-actor connection-id))`, not just that the principal is a service.
- **Money** is an exact decimal string. A price or cost whose currency differs from `Workspace Currency` stays blank.
- No real Ozon account, push, deployment or database deletion without a person's explicit go-ahead. Never commit `.data`, keys or captures.

## Where code lives

| Namespace | Responsibility |
|---|---|
| `tover.marketplace.connector` | secrets, verification, HTTP (exists on the branch) |
| `tover.marketplace.connections.{model,web}` | 40 (exists) |
| `tover.marketplace.sync.ozon` | Ozon payload → observation (exists) |
| `tover.marketplace.sync.{observations,model}` | 41; observation schemas and revision logic move out of the 1210-line `sync/model.clj` in plan 1 |
| `tover.marketplace.products.{candidates,model,web}` | 42 |
| `tover.marketplace.warehouses.{model,web}` | 43 |
| `tover.marketplace.provenance` | Models and pure rules that inventory must read: cutover entries, baselines, imported entries, source acceptances. Depends on no other marketplace namespace, so `entries.model` and `acceptance.model` may require it |
| `tover.marketplace.cutover.{model,web}` | 44 |
| `tover.marketplace.operations.{steps,proposals,model}` | 45; its screen is the Operations page (`inventory/operations/web.clj`) |
| `tover.marketplace.moves.model` | 45 b, c |
| `tover.marketplace.transit.{model,web}` | 46 |
| `tover.marketplace.grid` + `resources/public/marketplace.js` | One table workbench over `inventory-grid`, used by every page above |

Pages (decided 2026-11-26): no Marketplace tab. The connections list is a section of `/settings`; the connection is `/settings/marketplace/:connection-id`, with `/products`, `/warehouses`, `/cutover`, `/differences` and `/transit` below it. Imported operations have no page of their own: they are reviewed and accepted on the Operations page (`/operations`), in the shared draft, under an Imported filter (board 45 `Inventory Review`). A connection names no store; imported entries leave Store blank for a person to fill or leave empty.

## Decisions needed from a person

Found while planning. Each is missing from the board; the default is what the plans assume. Confirm, then it goes on the board before the named task.

1. **Variant-separating attributes** (plan 1 task 5). Chapter 42 compares attributes "Ozon marks as separating variants", but `Attribute Definition Observed` has no such field. Default: add `separates variants | yes/no | no` (Ozon `is_aspect`).
2. **Discarding an imported entry** (plan 4 task 4). Intake would propose it again at once. Default: discarding an imported entry sets its operation aside in the same commit; discarding a source correction suppresses it until the next source revision.
3. **Handover then cancellation** (plan 1 task 6). If Ozon stops sending the handover time once a posting is cancelled, the Sale would look withdrawn and stock would come back. Default: a handover time once observed is kept in later cancelled revisions.
4. **Snapshot retention** (plan 5 task 3). Finding the last agreeing snapshot needs older figures. Default: keep the last 30 finished `stock-by-warehouse` passes, delete older figures; other stock views keep the latest only.
5. **Finished states first** (plan 4 task 2). Read top to bottom, the next-step table lets a set-aside operation of an unmatched listing show `match product`. Default: `history`, `set aside` and `no effect` are decided before the other rows.

## Live check (before plan 4 is deployed)

One real account, read-only, with the owner's go-ahead. Record results on slice `Record source observations`:
- `fact_delivery_date` / `delivering_date` equal the physical handover, FBS and FBO;
- `logistic.final_moment` equals receipt;
- `present` includes reserved units in `stock-by-warehouse`;
- ~~`place.id` of an FBO return is a stock warehouse~~ (checked 2026-11-25: it is not, so returns wait for a member);
- cancelled-after-handover postings still carry their date (decision 3).

If a check fails, that kind is recorded incomplete and is not accepted automatically.
