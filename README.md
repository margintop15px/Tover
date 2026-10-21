# Tover

The main application is Clojure, using Datastar, http-kit, Reitit, Chassis and RDF4J NativeStore. Authentication and workspace isolation are implemented; inventory remains a UI prototype and RDF4J remains provisional. Hosted and self-hosted Supabase Auth/Postgres/PostgREST use the same adapters, selected through Integrant. The legacy Next.js application has been removed; SQL history and selected [legacy contracts](docs/legacy-contracts/README.md) are retained.

## Run

Requires JDK 21+ and the Clojure CLI. Initialize and start the [local identity profile](docs/identity-deployment.md) first. From the repository root:

```sh
clojure -M:dev
```

Open <http://localhost:3030> and sign in with an existing local account or create one. Default development reads `deployment/local-identity/.env` directly and uses local Supabase Auth, Postgres sessions and captured email. No shell exports are required. It starts a loopback nREPL and enables Malli instrumentation. Inventory persists in `.data/inventory`; a new workspace is empty.

For the offline seeded prototype, run `TOVER_IDENTITY_MODE=fixtures clojure -M:dev` and sign in with `owner@example.test` / `tover-development`. This mode uses in-memory sessions and seeds three opening declarations into the fixture workspace without overwriting accepted edits. `TOVER_IDENTITY_MODE=supabase` uses explicit `TOVER_*` environment settings for a hosted or custom development target; see [Identity deployments](docs/identity-deployment.md).

`clojure -M:run` requires explicit identity/database credentials, a 32-byte Base64 session encryption key and an HTTPS application origin. It has no fixture fallback, examples or instrumentation. Workspaces use separate RDF graphs; old anonymous prototype graphs remain untouched and excluded. A fresh workspace is empty.

Development uses [ring-hot-reload](https://github.com/brettatoms/ring-hot-reload) and Ring's `wrap-reload`. Saving Clojure files under `src` reloads code and refreshes open browser pages. CSS and JavaScript changes under `resources` also refresh the page. Full refresh reinitializes Datastar and RevoGrid and clears unfinished browser input. Changes to declaration routes, page hierarchy, route policies, `dev`, handler/middleware construction or resource configuration need `user/refresh!` to rebuild the router; dependency changes need a REPL restart. Production has no reload middleware or file watcher.

```clojure
(user/stop!)
(user/start!)
(user/refresh!)
```

Integrant owns the repository, realtime connections, handler and HTTP server. Shutdown closes streams and drains HTTP before closing RDF.

## UI flow

Stock (`/`), Operations (`/operations`), Workspaces and Team share one server-rendered shell, navigation and theme. Direct visits return complete HTML; internal links fetch Datastar page patches and update browser history after rendering. Back/Forward restores the URL and scroll position. Authentication and active workspace changes use complete document loads.

Pages declare GET URLs in `defpage` metadata. Bel builds their Reitit routes and resolves the matched declaration through its registry. Layouts use explicit `:parent` references; Tover owns their documents, appearance and access policies. All application POST commands use `defaction` at canonical `/action/<namespace>/<name>` URLs, including authentication and workspace changes. A same-page effect refreshes content without adding history; a different destination adds an entry after its patch is applied. Authentication forms submit complete documents; workspace changes return document reload effects.

The stock screen uses the native RevoGrid 4.28.3 web component. The browser initializer configures columns and interaction through native properties. Datastar overlays temporary browser edits on the accepted `_stockRows` signal. Clojure owns saved stock, business validation and acceptance.

Goods, Quantity, Unit, Location and Article support inline editing. Text editors suggest matching values and allow new text; Quantity uses a plain editor. Evidence is read-only and system-set. All columns support header sorting. The grid fits its visible rows up to 65% of the viewport. A permanent change bar shows changed/deleted/added row counts and provides Add row, revert, before/after preview and submit controls.

**Add row** inserts a blank green draft and opens Goods editing. The top smart input builds a row from `field:value` chips: type to match fields or known values, choose a field, then choose or enter its value. Enter creates a chip. Click a chip to edit it or × to remove it. Cmd/Ctrl+Enter or the trailing plus adds the row. New rows receive **Manual entry** evidence.

Click a row number to select the whole row, then press Delete to mark it for deletion. Cmd/Ctrl + Delete does the same from a selected cell. Deleted rows stay visible in muted red and stop accepting edits; Revert restores them. Selection follows row identity through sorting and filtering.

All grid edits and new rows are UI drafts. **Submit changes** in the diff or the toolbar keeps new rows and edited values, hides marked rows and clears the pending diff in this tab. Later previews compare against the last local submission; **Revert all changes** restores that local baseline. Deleting an unsubmitted new row removes it immediately. Leaving inventory or reloading restores saved stock. Submit makes no persistence request.

Accepted stock updates the grid's `source` property after the active editor closes and any pending proposal is resolved. Background refresh preserves the current review, reason and history. Pinned RevoGrid assets and its MIT license remain in `resources/public/vendor`. RevoGrid keeps its Community attribution.

The top input now creates rows. Existing Clojure smart-adjustment actions remain available to direct callers; the grid and row-entry Submit controls remain UI simulations.

**Operations** uses a read-only RevoGrid with the same spreadsheet appearance as Stock. Each operation has one overview row: date, type, summary, locations/party, items and reason/evidence. Search and native column sorting/filtering remain available. Expanding an operation shows its details: field-level before/after changes, paired transfer locations, or consumed inputs and produced outputs.

In development, **Demo data** switches between illustrative inventory/history and recorded local stock. The examples live under `dev/tover/operations/` and do not change accepted stock or recorded history. Production has no example payloads or toggle. The shared navigation has no demo title.

The arrow beside each Goods name expands that stock line's history as another read-only RevoGrid. Shared operations appear for every affected line. Their expanded details emphasize that line's role and retain the other inputs/outputs. Multiple lines can stay open while sorting or editing.

UI Submit also appends local history entries for additions, edits and deletions. These entries have a **Local** badge and appear in both history views. Unsubmitted drafts are excluded. Switching Stock/Operations preserves drafts and local submissions. Leaving inventory for Workspaces or Team discards them without confirmation; returning queries saved stock again. Reloading also clears local submissions and their history. Recorded operations are read-only and remain in Clojure/RDF.

Domain UI components and shared components in `tover.shared.ui` are pure Hiccup functions. The props map contains data and event properties such as `:on-click`, `:on-input`, `:on-keydown` and `:init`. Use trailing children only when a component needs caller-supplied content. Views supply direct action expressions. See [AGENTS.md](AGENTS.md) for component conventions. There are no custom client namespaces or application objects on `window`.

The browser holds input strings, a stock-change flag and temporary editor values. Adjustment reviews are rendered by the server. Actions update stock signals; reviews, notices and history replace their own regions.

`@action` and `@view` use Datastar request defaults. An optional third argument supplies request options for that call:

```clojure
(->expr (@action :tover.stock.web/accept ~payload {:requestCancellation "disabled"}))
(->expr (@view :tover.stock.web/stock {:query $query} {:retry "never"}))
```

## Updates and storage

`GET /`, `/operations`, `/workspace` and `/team` render complete documents or Datastar patches according to the request. Generated `POST /view/<namespace>/<name>` routes render fragments; `POST /action/<namespace>/<name>` routes execute actions. Datastar responses use short SSE streams with HTML, signal or page effects; ordinary authentication forms receive HTML or redirects.

`GET /_bel/realtime/stream` opens a persistent stream with its topics in the initial request. An opaque HttpOnly cookie identifies a server-side session; each document has its own tab ID. Internal navigation retains one workspace stream. Production stores encrypted Auth tokens in private Postgres sessions. Refreshes serialize under a row lock. Every protected request verifies the current user and membership. Mutations require CSRF tokens, the configured origin and the current page context.

Streams and publications carry the server-derived workspace. Delivery rechecks the recipient's current session and membership. Logout and workspace changes close old streams; other tabs reload and stale requests return `409 WORKSPACE_CHANGED`. Stock notifications set a flag, and a direct Datastar effect requests the current stock view. Publication remains direct, without a delivery worker or subscription handshake.

Bel declarations provide typed input and an optional `:output` contract. Views default to `Result`; actions default to `ActionResult`, which also accepts native Ring responses for standalone HTML. Instruction action results can carry top-level `:session`, `:session/key` and `:cookies` entries, applied to the rendered response. Pages return one rooted `{:type :elements :html ...}` instruction. `{:type :page :id :qualified/page :input {}}` is a terminal navigation effect; it cannot be mixed with fragment or signal instructions. A page can name a `:parent`; parent renderers accept `[props & children]` and render the child in that slot. Queries run root to leaf, composition runs leaf to root, and the whole result is prepared before delivery. The document component supplies assets and bootstrap only on complete loads. Routes declare authentication, workspace and manager policies through Reitit middleware. Query functions own their result schemas. Startup requires discovered namespaces under `tover`, then registers their declaration Vars without scanning all loaded namespaces. Handlers resolve current Vars for REPL edits. `bel.validation` handles Malli validation and wire codecs. The Datastar header alone does not authorize writes.

Streams infer their topic from the namespace and declaration name. Their options map is optional; `:schema` describes the payload passed directly to the body:

```clojure
(defstream changed
  [_ctx _payload]
  {:type :signals :data {:stockChanged true}})

(bel.realtime/publish! realtime context {:topic :tover.stock.web/changed :data {}})
```

Pass shared app context without the request and include `:scope-id` from the trusted workspace. Delivery uses freshly authorized recipient context. Connections retain only stream/topic and session/workspace references, without request objects or credentials.

RDF holds immutable opening declarations and accepted adjustments. Current stock is derived from their quantities. Acceptance validates input and records an atomic history entry using the current quantity. Quantity input stays a string until the server parses it, preserving integer precision.

There are no revisions, stale-baseline checks, edit replay, request queues, draft reconciliation or delivery acknowledgements. Leaving inventory or reloading loses unfinished input. Failed navigation leaves the current page and its input intact. Disconnected notifications are lost; opening or reconnecting a stream only registers its topics. Refresh after missed updates is deferred. If delivery is uncertain, refresh and inspect stock/history before submitting again. Concurrent edits use the current stock at acceptance, which can differ from the preview.

## Checks

```sh
clojure -M:dev:test
clojure -M:dev:test --focus tover.stock.store-test
clojure -M:fmt check
```

Tests cover validation, RDF transactions, persistence, stock/history, pure component rendering, page hierarchy, direct action flows and transport cleanup. Browser checks cover history, navigation failures, draft lifetime, forms, workspace resets and shared light/dark styling. Instrumentation runs during the suite. See [styleguide.md](styleguide.md) for formatting.

Pinned versions: Datastar Clojure SDK/adapter `1.0.0-RC11`, local browser runtime `1.0.4`, http-kit `2.9.0-beta4`, Reitit `0.11.0`, Chassis `1.0.365`. Imports, marketplace integration and production cutover remain later work.


## Browser checks

Internal forms use Datastar form data with mutation retries disabled. Business errors patch the page and preserve non-secret fields; authentication, authorization and CSRF failures retain their HTTP error statuses. The pinned browser runtime only consumes HTTP 200 patch bodies, so rendered business errors use 200 with the existing error-code header. Ordinary form fallbacks retain normal redirects and error statuses.

The small Playwright package is isolated under `test/browser`; Node is not an application runtime. Its servers use disposable local stores and never read `.env.local`.

```sh
npm --prefix test/browser ci
cd test/browser
npx playwright install chromium
cd ../..
npm --prefix test/browser test
```

## Code organization

Clojure source and tests are grouped by domain: `auth`, `workspaces`, `teams`, `stock` and `operations`. Domain folders use `core.clj` for their main contracts or compact implementation. Web declarations, substantial UI, persistence and adapters stay with their domain. `tover.inventory.workbench` composes Stock and Operations without depending on their route declarations.

`tover.shared` owns reusable UI/forms, the application document/shell, error rendering, common schemas, RDF infrastructure and Supabase transport. `tover.app` owns Integrant composition. Development fixtures remain under `dev`; browser assets remain in `resources/public`. Tests mirror these domains, with cross-domain flows and reusable helpers kept separate.

Declaration IDs and generated action/view URLs follow their new namespaces. GET URLs and persisted RDF identifiers are unchanged. Restart the development REPL and reload open browser tabs after this namespace migration.

## Repository boundaries

[Current specifications](docs/README.md) describe the planned product. [Legacy contracts](docs/legacy-contracts/README.md) retain identity, Ozon and import behavior without the old application implementation. SQL migrations and independent disposable-Postgres checks remain unchanged. The [RDF experiment](experiments/inventory-rdf/README.md) is historical evidence, with an older containment model.

Milestone 1 moved code and tooling. Milestone 2 adds identity, workspace isolation and the local self-hosted integration profile. Real grid submission, the new inventory model and production packaging remain later work. Existing local prototype data moved from `tover_v2/app/.data` to root `.data`; it was not reset or assigned to a workspace. A source/data recovery copy for that migration remains in the ignored `.migration-backups/2026-10-19-milestone-1/` directory.
