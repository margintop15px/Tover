# Tover

The main application is now Clojure, using Datastar, http-kit, Reitit, Chassis and RDF4J NativeStore. This is still a UI prototype with one local workspace and no authentication. RDF4J remains provisional. The legacy Next.js application has been removed; Supabase SQL history and selected [legacy contracts](docs/legacy-contracts/README.md) are retained for later migration steps.

## Run

Requires JDK 21+ and the Clojure CLI. From the repository root:

```sh
clojure -M:dev
```

Open <http://localhost:3030>. Development seeds three opening declarations, starts a loopback nREPL on an assigned port, and enables Malli instrumentation. Data persists in `.data/inventory`. Seeds do not overwrite accepted edits. `clojure -M:run` starts without development seeds, examples or instrumentation. A fresh production store is empty; existing accepted local stock is preserved.

Development uses [ring-hot-reload](https://github.com/brettatoms/ring-hot-reload) and Ring's `wrap-reload`. Saving Clojure files under `src` reloads code and refreshes open browser pages. CSS and JavaScript changes under `resources` also refresh the page. Full refresh reinitializes Datastar and RevoGrid and clears unfinished browser input. Changes to `dev`, handler/middleware construction or resource configuration need `user/refresh!`; dependency changes need a REPL restart. Production has no reload middleware or file watcher.

```clojure
(user/stop!)
(user/start!)
(user/refresh!)
```

Integrant owns the repository, realtime connections, handler and HTTP server. Shutdown closes streams and drains HTTP before closing RDF.

## UI flow

The stock screen uses the native RevoGrid 4.28.3 web component. The browser initializer configures columns and interaction through native properties. Datastar overlays temporary browser edits on the accepted `_stockRows` signal. Clojure owns saved stock, business validation and acceptance.

Goods, Quantity, Unit, Location and Article support inline editing. Text editors suggest matching values and allow new text; Quantity uses a plain editor. Evidence is read-only and system-set. All columns support header sorting. The grid fits its visible rows up to 65% of the viewport. A permanent change bar shows changed/deleted/added row counts and provides Add row, revert, before/after preview and submit controls.

**Add row** inserts a blank green draft and opens Goods editing. The top smart input builds a row from `field:value` chips: type to match fields or known values, choose a field, then choose or enter its value. Enter creates a chip. Click a chip to edit it or × to remove it. Cmd/Ctrl+Enter or the trailing plus adds the row. New rows receive **Manual entry** evidence.

Click a row number to select the whole row, then press Delete to mark it for deletion. Cmd/Ctrl + Delete does the same from a selected cell. Deleted rows stay visible in muted red and stop accepting edits; Revert restores them. Selection follows row identity through sorting and filtering.

All grid edits and new rows are UI drafts. **Submit changes** in the diff or the toolbar keeps new rows and edited values, hides marked rows and clears the pending diff in this tab. Later previews compare against the last local submission; **Revert all changes** restores that local baseline. Deleting an unsubmitted new row removes it immediately. Reloading restores saved stock. Submit makes no persistence request.

Accepted stock updates the grid's `source` property after the active editor closes and any pending proposal is resolved. Background refresh preserves the current review, reason and history. Pinned RevoGrid assets and its MIT license remain in `resources/public/vendor`. RevoGrid keeps its Community attribution.

The top input now creates rows. Existing Clojure smart-adjustment actions remain available to direct callers; the grid and row-entry Submit controls remain UI simulations.

**Operations** uses a read-only RevoGrid with the same spreadsheet appearance as Stock. Each operation has one overview row: date, type, summary, locations/party, items and reason/evidence. Search and native column sorting/filtering remain available. Expanding an operation shows its details: field-level before/after changes, paired transfer locations, or consumed inputs and produced outputs.

In development, **Demo data** switches between illustrative inventory/history and recorded local stock. The examples live under `dev/tover/` and do not change accepted stock or recorded history. Production has no example payloads, badge or toggle.

The arrow beside each Goods name expands that stock line's history as another read-only RevoGrid. Shared operations appear for every affected line. Their expanded details emphasize that line's role and retain the other inputs/outputs. Multiple lines can stay open while sorting or editing.

UI Submit also appends local history entries for additions, edits and deletions. These entries have a **Local** badge and appear in both history views. Unsubmitted drafts are excluded. Switching views preserves drafts; reloading clears local submissions and their history. Recorded operations are read-only and remain in Clojure/RDF.

Components in `tover.ui` are pure Hiccup functions. The props map contains data and event properties such as `:on-click`, `:on-input`, `:on-keydown` and `:init`. Use trailing children only when a component needs caller-supplied content. Views supply direct action expressions. See [AGENTS.md](AGENTS.md) for component conventions. There are no custom client namespaces or application objects on `window`.

The browser holds input strings, a stock-change flag and temporary editor values. Adjustment reviews are rendered by the server. Actions update stock signals; reviews, notices and history replace their own regions.

`@action` and `@view` use Datastar request defaults. An optional third argument supplies request options for that call:

```clojure
(->expr (@action :tover.actions/accept ~payload {:requestCancellation "disabled"}))
(->expr (@view :tover.views/stock {:query $query} {:retry "never"}))
```

## Updates and storage

`GET /` renders the page. `POST /view/{*id}` renders fragments; `POST /action/{*id}` executes an action. Both return short Datastar SSE responses with HTML or signal patches.

`GET /_bel/realtime/stream` opens a persistent stream with its topics in the initial request. A signed Ring cookie identifies the session; each page has its own tab ID. Stock notifications set a flag, and a direct Datastar effect requests the current stock view. Datastar manages the connection. Publication sends directly to connected subscribers; there is no delivery worker or subscription handshake.

Bel declarations provide typed input and an optional `:output` contract, which defaults to `Result`. Pages and views return element, signal or view instructions; Hiccup belongs inside `{:type :elements :html ...}`. Initial pages emit elements only. Query functions own their result schemas. Startup requires discovered namespaces under `tover`, then registers their declaration Vars without scanning all loaded namespaces. Handlers resolve current Vars for REPL edits. `bel.validation` handles Malli validation and wire codecs. Ring requires the Datastar header for writes.

Streams infer their topic from the namespace and declaration name. Their options map is optional; `:schema` describes the payload passed directly to the body:

```clojure
(defstream stock
  [_ctx _payload]
  {:type :signals :data {:stockChanged true}})

(bel.realtime/publish! realtime context {:topic :tover.streams/stock :data {}})
```

Pass shared app context without the request. Delivery adds session and tab IDs from the connection keys; connections store only the SSE stream and topics.

RDF holds immutable opening declarations and accepted adjustments. Current stock is derived from their quantities. Acceptance validates input and records an atomic history entry using the current quantity. Quantity input stays a string until the server parses it, preserving integer precision.

There are no revisions, stale-baseline checks, edit replay, request queues, draft reconciliation or delivery acknowledgements. Reloading the page loses unfinished input. Disconnected notifications are lost; opening or reconnecting a stream only registers its topics. Refresh after missed updates is deferred. If delivery is uncertain, refresh and inspect stock/history before submitting again. Concurrent edits use the current stock at acceptance, which can differ from the preview.

## Checks

```sh
clojure -M:dev:test
clojure -M:dev:test --focus tover.store-test
clojure -M:fmt check
```

Tests cover validation, RDF transactions, persistence, stock/history, pure component rendering, direct action flows and transport cleanup. Instrumentation runs during the suite. See [styleguide.md](styleguide.md) for formatting.

Pinned versions: Datastar Clojure SDK/adapter `1.0.0-RC11`, local browser runtime `1.0.4`, http-kit `2.9.0-beta3`, Reitit `0.10.1`, Chassis `1.0.365`. Auth, imports, marketplace integration and production recovery are outside this prototype.


## Browser checks

The small Playwright package is isolated under `test/browser`; Node is not an application runtime. Its servers use disposable local stores and never read `.env.local`.

```sh
npm --prefix test/browser ci
cd test/browser
npx playwright install chromium
cd ../..
npm --prefix test/browser test
```

## Repository boundaries

[Current specifications](docs/README.md) describe the planned product. [Legacy contracts](docs/legacy-contracts/README.md) retain identity, Ozon and import behavior without the old application implementation. SQL migrations and independent disposable-Postgres checks remain unchanged. The [RDF experiment](experiments/inventory-rdf/README.md) is historical evidence, with an older containment model.

Milestone 1 moved code and tooling only. Authentication, workspace isolation, real grid submission, the new inventory model and production packaging remain later work. Existing local prototype data moved from `tover_v2/app/.data` to root `.data`; it was not reset or reinterpreted. A verified source/data recovery copy for this local migration is in the ignored `.migration-backups/2026-10-19-milestone-1/` directory. Credentials and private captures remain at their original paths and are excluded from that source archive.
