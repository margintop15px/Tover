# Bel P5 Profile (Chapters 03/03a) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Display name and account language become Bel commands on the PostgreSQL profile. The signed-in identity (user, language, profile completion, memberships) comes from JWT claims instead of per-request HTTP calls. Users without a saved name are routed to profile setup. The live language writer moves from Auth metadata to the profile.

**Architecture:**
- **Migration 037:**
  - Adds `language`, `name_revision` and `language_revision` to `public.profiles`.
  - Lets `tover_app` UPDATE only those columns plus `display_name`, only on its own row.
  - Adds `public.custom_access_token_hook`, so every issued token carries `language`, `profile_complete`, `email_confirmed` and the active `memberships`.
- **Identity:**
  - A new `tover.accounts.profile` slice owns the `profiles` model, Set Display Name, Change Account Language and My Profile.
  - `security/authenticate` decodes claims through `Auth/token-identity` (no HTTP).
  - Bel's `:tover.auth/refresh-claims` effect refreshes the actor's sessions after a profile save, so new claims apply immediately.
  - Legacy membership writers (accept invites, create workspace) refresh the current session inline.
- **Gate:** a new `:profile` access policy and a `:profile-required` redirect carrying `return-to` gate every other protected route.

**Tech Stack:** Clojure, Malli, next.jdbc/HoneySQL, Integrant, Datastar, Supabase GoTrue v2.197 custom access token hook (`pg-functions`), PostgreSQL RLS, Kaocha.

**Spec:**
- `docs/bel-architecture.md` §8 (board-driven loop) and §9 P5 ("Profile, name, language built on the new structure via §8" — done when "Board scenarios pass; profile writers cut over").
- Board chapters 03 `78b68815-3d9d-4a48-acaf-c07629523f31` and 03a `aec262e9-fa50-4bdd-bcd6-5ab1f3caf645`.
- `docs/bel-onboarding-mapping.md` (rows 03/03a, "Fixed defaults").
- User decisions 2026-11-11:
  - the setup gate is included;
  - user, language, profile completion and memberships come from JWT claims;
  - revocation is seen on token refresh (≤ `JWT_EXP`, 1 h) on legacy routes.

Branch `bel/p5-profile` from `bel/p4-system` (P1–P4 not merged).

## Global Constraints

- REPL-first (AGENTS.md): `:reload` only, never `:reload-all`; `clj-paren-repair`; `clojure -M:xref` before changing callers.
- Every fn carries `:malli/schema`. Prefer open Malli maps. No new dependencies.
- Migration `supabase/migrations/037_profile_preferences.sql` is the only new migration. Never edit 033–036.
- Database safety: no destructive SQL. 037 only adds columns, policies, a trigger and a function, and fills NULL `language` from Auth metadata.
- Display name: "Nonblank trimmed Unicode text, maximum 100 characters; duplicates allowed" (mapping "Fixed defaults"; this settles the board's Display Name Length Limit hotspot). Language: `"en"` | `"ru"`.
- Board rules:
  - Unchanged values emit no event.
  - A stale edit of the same field fails `:stale-profile` and carries the latest value.
  - Name and language saves never conflict with or overwrite each other.
  - A repeated `command-id` returns the original outcome (C2).
  - A form opened for account A and submitted under B is `:forbidden`.
  - A restricted recovery session (`password-required?`) cannot edit the profile.
- JWT trust: access tokens are read only from the server-side session store, where they were stored from Auth's HTTP response, so `token-identity` decodes the payload without verifying the signature. Missing Tover claims fail closed with `{:type :configuration}` (HTTP 503), never as empty memberships or an incomplete profile.
- Out of scope:
  - Auth email templates choosing language from the profile (emails stay bilingual);
  - chapter 02's full My Next Authorized Step (the gate only returns to `return-to` or `/`);
  - Bel Create Workspace cutover.
- Tests: `clojure -M:dev:test` (baseline 596 tests / 9829 assertions) and `scripts/test-bel-workspace.sh` (baseline 40/271 + restart 2/13).

## Review Focus

1. Tokens issued without the hook (no `memberships` claim) fail closed with 503 `:configuration` on every protected route → test in Task 3.
2. A profile form for account A submitted under B's session is `:forbidden` and B's profile is unchanged → scenario in Task 2.
3. Two tabs at the same revisions: one saves the name, the other saves the language. Both succeed, and the same-field stale save fails `:stale-profile` with the latest value → scenario plus PG test in Task 2.
4. The gate preserves the requested destination, and `return-to` accepts only a same-origin path (`//evil.test`, `https://evil.test`, `/\evil` fall back to `/`) → test in Task 4.
5. After Continue saves a name, the next request is not gated again (claims refreshed). A failed refresh does not fail the accepted save → test in Task 4.

---

### Task 1: Migration 037 and its rollout in every profile

**Files:**
- Create: `supabase/migrations/037_profile_preferences.sql`
- Modify:
  - `deployment/local-identity/identity.sh` (`up`: apply 033 only when `app_private.sessions` is absent, then 034–037);
  - `deployment/local-identity/compose.yaml` and `deployment/production/compose.yaml` (`auth` env: `GOTRUE_HOOK_CUSTOM_ACCESS_TOKEN_ENABLED: 'true'`, `GOTRUE_HOOK_CUSTOM_ACCESS_TOKEN_URI: pg-functions://postgres/public/custom_access_token_hook`);
  - `deployment/production/production.sh` (`migrate`: same 033 guard, then 034–037);
  - `deployment/production/release.py` (`PUBLIC_FILES` gains 035–037);
  - `scripts/test-bel-workspace.sh` (apply 037; rerun 037 once like 036).
- Test: `test/workspace_creation_integration.clj` (or a new `test/profile_integration.clj` registered in the script the same way)

**Interfaces:**
- Produces, on `public.profiles`:
  - `language text CHECK (language IN ('en','ru'))`;
  - `name_revision bigint NOT NULL DEFAULT 0`, `language_revision bigint NOT NULL DEFAULT 0`;
  - `CHECK (display_name IS NULL OR (char_length(display_name) <= 100 AND btrim(display_name) <> '')) NOT VALID`.
- Produces: a `BEFORE INSERT` trigger on `public.profiles` that sets a NULL `language` from `auth.users.raw_user_meta_data->>'locale'` when it is `en`/`ru` (signup keeps its chosen language without editing 033's function), plus a one-time `UPDATE … WHERE language IS NULL` with the same rule.
- Produces grants and policy:
  - `GRANT UPDATE (display_name, name_revision, language, language_revision) ON public.profiles TO tover_app`;
  - `CREATE POLICY bel_profile_update ON public.profiles FOR UPDATE TO tover_app USING/WITH CHECK (user_id = nullif(current_setting('request.jwt.claim.sub', true), '')::uuid)` (same claim pattern as 035).
- Produces: `public.custom_access_token_hook(event jsonb) RETURNS jsonb`, `STABLE SECURITY DEFINER SET search_path = ''`. It returns `event` with `claims` extended by:
  - `language` (text or null);
  - `profile_complete` (display name nonblank and ≤ 100);
  - `email_confirmed` (`email_confirmed_at IS NOT NULL`);
  - `memberships`: a JSON array of `{workspace_id, name, role, created_at}`, active only, ordered like `private.my_workspaces()`.

  Execute is granted only to `supabase_auth_admin`, revoked from `PUBLIC`, `anon` and `authenticated`.
- The 033 rerun guard: 033's exact-shape check rejects `profiles` once 037 adds columns, so both scripts skip 033 when `to_regclass('app_private.sessions')` is not null.

- [ ] **Step 1: Write the failing integration tests:**
  - `profile-hook-claims`: a user with name "Alice", locale `ru` in metadata and one active and one revoked membership. The admin calls `select public.custom_access_token_hook(jsonb_build_object('user_id', id, 'claims', '{}'::jsonb))`. Expected: `language "ru"`, `profile_complete true`, `email_confirmed true`, exactly the active membership.
  - `profile-update-is-own-row-and-columns-only`: as `tover_app` with `request.jwt.claim.sub` = Alice, updating Bob's `display_name` affects 0 rows, and updating Alice's `created_at` fails with 42501.
  - `hook-is-not-callable-by-the-app`: `tover_app` executing the hook fails with 42501.
- [ ] **Step 2: Run** `scripts/test-bel-workspace.sh` → the three tests FAIL (no column/function).
- [ ] **Step 3: Implement** 037 and the script, compose and release changes. Check scripts with `bash -n` and `python3 -m py_compile deployment/production/release.py`.
- [ ] **Step 4: Run** `scripts/test-bel-workspace.sh` → all pass, including the 037 rerun. `clojure -M:dev:test` → 0 failures.
- [ ] **Step 5: Commit** `feat(db): profile language, revisions and token claims (037)`

### Task 2: Profile slice in Bel

**Files:**
- Create:
  - `src/tover/accounts/profile/model.clj`;
  - `src/tover/accounts/persistence.clj` (`tables`);
  - `test/tover/accounts/profile_test.clj` (scenarios with `bel.runtime/decide`).
- Modify:
  - `src/tover/workspaces/create/model.clj` and `src/tover/workspaces/persistence.clj` (delete the `profiles` model and table entry; refer to `:tover.accounts.profile.model/profiles`);
  - `src/tover/shared/bel.clj` (`:tables (merge workspaces/tables accounts/tables)`; `:resources` gains `:auth` and `:session-store`);
  - `src/tover/app.clj` (`::bel-system` gains `:auth (ig/ref ::auth) :session-store (ig/ref ::session-store)`).
- Test: the integration file from Task 1 (PG behavior of the two commands)

**Interfaces:**
- Produces `tover.accounts.profile.model`:
  - `DisplayName`: `[:string {:min 1 :max 100 :decode/string trim}]`. Blank after trim fails `:validation`.
  - `Language`: `[:enum "en" "ru"]`.
  - `(defmodel profiles {:id :user-id :store :directory :schema Profile :policy ::profile-owner :events {::display-name-changed {:set [:display-name :name-revision]} ::account-language-changed {:set [:language :language-revision]}}})`, where `Profile` is `[:map [:user-id :uuid] [:display-name {:optional true} :string] [:language {:optional true} Language] [:name-revision :int] [:language-revision :int]]`.
  - `::profile-owner` policy: `confirmed?`, not `password-required?`, `(= user-id (:user-id scope))`, and, when the input has `:account`, `(= (:account input) user-id)`.
  - `(defcommand set-display-name {:scope :user :input [:map [:account :uuid] [:display-name DisplayName] [:name-revision :int]] :policy ::profile-owner :cofx [[:profile ::profiles …]] :emits #{::display-name-changed}})`:
    - an unchanged name emits no event;
    - a differing `:name-revision` throws `{:type :stale-profile :latest {:display-name … :name-revision …}}`;
    - otherwise it emits `{:user-id actor :display-name name :name-revision (inc rev)}`.
    - Every outcome returns one `:tover.auth/refresh-claims` effect, in the fx descriptor shape `bel.runtime` checks today.
  - `change-account-language` follows the same rules on `:language` / `:language-revision`.
  - `(defreadmodel my-profile {:scope :user :policy ::profile-owner :output Profile})`.
- Produces `tover.accounts.persistence/tables`: `{::profile/profiles {:table :public.profiles :columns {:user-id :user_id :display-name :display_name :language :language :name-revision :name_revision :language-revision :language_revision}}}`.
- The effect method `:tover.auth/refresh-claims` is defined in Task 4. Until then, register a no-op test method in the scenario tests only.

- [ ] **Step 1: Write the failing scenarios.** Each board example is one `deftest` using `decide`. Facts: `{:profile {...} :actor alice}`; context principal `{:user-id alice :confirmed? true :password-required? false}`.
  - `first-name-is-accepted`: no name, rev 0 → one `::display-name-changed` with `"Алиса"` (from input `"  Алиса  "`) and rev 1.
  - `unchanged-name-emits-nothing`: `[]` events; fx contains refresh.
  - `blank-name-is-invalid`: `"   "` → `:validation`.
  - `stale-name-is-rejected-with-latest`: saved rev 2 "Bob", input rev 1 "Rob" → `:stale-profile`, `(:latest data)` = `{:display-name "Bob" :name-revision 2}`.
  - `language-change-ignores-name-revision`: name rev 5, language rev 0, input language rev 0 → event.
  - `form-for-another-account-is-forbidden`: `:account bob` under alice → `:forbidden` (`runtime/authorize!` in the scenario, or an `execute!` with the memory store from `runtime_test`).
  - `recovery-session-is-forbidden`: `password-required? true` → `:forbidden`.
  - `name-longer-than-100-is-invalid`: 101 chars → `:validation`.

  Integration:
  - `name-and-language-saves-do-not-overwrite`: execute both commands from rev 0/0 on Alice; then name = new, language = new, revisions 1/1.
  - `repeated-command-returns-original-after-later-edit`: save "A" (C1), save "B" (C2), replay C1 → original receipt; name still "B".
- [ ] **Step 2: Run** `clj-nrepl-eval -p <port> "(clojure.test/run-tests 'tover.accounts.profile-test)"` → FAIL (namespace missing).
- [ ] **Step 3: Implement** the Produces block. Move the `profiles` model with `clojure -M:xref` checks for every `::model/profiles` caller (Create Workspace cofx and `:bel/ref`s, its tests' `register` calls).
- [ ] **Step 4: Run** the scenarios → PASS; `scripts/test-bel-workspace.sh` → all pass; `clojure -M:dev:test` → 0 failures.
- [ ] **Step 5: Commit** `feat(accounts): display name and language commands`

### Task 3: Identity from token claims

**Files:**
- Modify:
  - `src/tover/auth/core.clj` (protocol: delete `current-user` and `set-locale!`, add `token-identity`);
  - `src/tover/auth/supabase.clj`;
  - `src/tover/workspaces/core.clj` and `src/tover/workspaces/supabase.clj` (delete `list-memberships`);
  - `dev/tover/identity.clj` (fixtures: tokens carry the same claims; fixture `token-identity` reads them);
  - `src/tover/auth/security.clj` (`authenticate`; new `refresh-claims!`);
  - `src/tover/auth/web.clj` (`complete-sign-in`, `finish-sign-in`: `refresh-claims!` after `accept-invites!`);
  - `src/tover/workspaces/web.clj` (`refresh-claims!` after `create-workspace!`).
- Test: `test/tover/auth/security_test.clj` (or the existing auth tests found with `clojure -M:xref` callers of `authenticate`), `test/tover/app_test.clj`

**Interfaces:**
- Produces: `(tover.auth.core/token-identity [provider access-token] → {:user User :profile-complete? boolean :memberships [workspaces/Membership]})`. `User` keeps `:user-id :email :email-confirmed?`, with `:locale` from the `language` claim when present. Missing `memberships`, `profile_complete` or `email_confirmed` → `(auth/fail! :identity-claims-missing 503 false)` with `:type :configuration`.
- Produces: `(tover.auth.security/refresh-claims! [services session-key] → nil)` runs `auth/refresh!` under `sessions/update-session!` and keeps `:version`.
- `authenticate` returns the same identity keys as today plus `:profile-complete?`. It makes no HTTP call unless the token is within 30 s of expiry.
- Supabase `token-identity`: base64url-decode the JWT payload with `java.util.Base64/getUrlDecoder` and the JSON reader already used in that namespace.

- [ ] **Step 1: Write the failing tests:**
  - `identity-comes-from-claims-without-http`: redef the Supabase HTTP `request!` to throw; a fixture session with fresh tokens still authenticates with memberships, locale and `:profile-complete?`.
  - `tokens-without-tover-claims-fail-closed`: GET of a protected route → 503, no redirect to login.
  - `accepting-invites-refreshes-claims`: after `finish-sign-in`, the session's identity lists the new membership.
- [ ] **Step 2: Run** the namespaces in the REPL → FAIL.
- [ ] **Step 3: Implement.** Use `clojure -M:xref` who-calls for `current-user`, `set-locale!` and `list-memberships`; Task 4 replaces `set-language`'s `set-locale!` call, so here make it a no-op for signed-in users until Task 4 (record a ruling).
- [ ] **Step 4: Run** `clojure -M:dev:test` → 0 failures; `scripts/test-bel-workspace.sh` → all pass.
- [ ] **Step 5: Commit** `feat(auth): identity from token claims`

### Task 4: Profile page, gate and language cutover

**Files:**
- Create: `src/tover/accounts/profile/web.clj`
- Modify:
  - `src/tover/auth/security.clj` (`IdentityPolicy` gains `:profile`; `:profile-required` check and redirect; `local-path`);
  - `src/tover/auth/web.clj` (`set-language` executes `::change-account-language` for signed-in users; uses `security/local-path`);
  - `src/tover/auth/sessions.clj` (`session-keys-for-user`);
  - `src/tover/accounts/profile/model.clj` (`defmethod runtime/effect! :tover.auth/refresh-claims`);
  - the app header/account menu view that renders the language control (add a "Profile" link and hidden `language-revision`).
- Test: `test/tover/accounts/profile_web_test.clj`, `test/tover/app_test.clj`

**Interfaces:**
- Consumes: Task 2 commands and `my-profile`; Task 3 `:profile-complete?` and `refresh-claims!`.
- Produces: `(tover.auth.security/local-path [value] → string)`. It returns `value` when it matches `#"/(?!/)[^\r\n\\]*"`, else `"/"`. `set-language` and the gate share it.
- Gate: after the password check, when the policy is not in `#{:optional :logout :finish :password :profile}` and the identity is not `:profile-complete?` → `failure 409 :profile-required`. For a non-Datastar GET, `response` returns 303 to `/account/profile?return-to=<url-encoded uri+query>`.
- Produces the `profile` page at `/account/profile` (`:tover/access :profile`). It shows the My Profile name and revisions, read-only email from the identity, and EN/RU buttons that are `formaction` submits to `set-language` inside the same form, so the draft name travels with them. Actions:
  - `save-name` → `::set-display-name`, `:then` 303 to `(local-path return-to)`.
  - `:catch :stale-profile` → the page with the latest name shown beside the proposed one.
  - Other errors → the page with the proposed name kept.
- `set-language`:
  - **Signed in:** executes `::change-account-language` with `:account` from a hidden form field and `:language-revision`.
  - **Draft present (from the profile form):** renders the profile page in the new language with that draft.
  - **Otherwise:** 303 to `(local-path return-to)`.
  - **`:stale-profile`:** a 409 page naming the latest language with a link back.
  - **Guest:** cookie only, as today.
- `(tover.auth.sessions/session-keys-for-user [store user-id] → [string])` for the JDBC and memory stores. The `:tover.auth/refresh-claims` effect calls `refresh-claims!` for each key of the actor, passing the effect's `:resources` (`{:auth :session-store …}`) as `services`. It logs and swallows failures (C6).

- [ ] **Step 1: Write the failing tests:**
  - `unnamed-user-is-sent-to-profile-with-return-to`: GET `/workspace/x` → 303 `/account/profile?return-to=%2Fworkspace%2Fx`.
  - `return-to-rejects-other-origins`: for `"//evil.test"`, `"https://evil.test"` and `"/\\evil"`, `local-path` → `"/"`.
  - `continue-saves-name-and-next-request-is-not-gated`: POST save-name, then GET the return-to path → 200.
  - `refresh-failure-does-not-fail-the-save`: `auth/refresh!` throws → save still 303, and the name is stored.
  - `language-switch-keeps-the-draft-name`: POST set-language with `display-name "Draft"` → body contains `value="Draft"` and `lang="ru"`.
  - `signed-in-language-writes-the-profile-not-auth`: fixture Auth metadata unchanged; profile language `"ru"`; next identity `:locale "ru"`.
  - `recovery-session-cannot-open-profile`: `password-required?` → 303 `/auth/password`.
- [ ] **Step 2: Run** the namespaces in the REPL → FAIL.
- [ ] **Step 3: Implement** the Produces block.
- [ ] **Step 4: Run** `clojure -M:dev:test` → 0 failures; `scripts/test-bel-workspace.sh` → all pass. Then the dev check: `identity.sh up` (ask the user first: it applies 037 and recreates the auth container), `user/start!`, sign in as a fixture-less local account without a name → profile page; save → requested page; switch language → header and page in RU.
- [ ] **Step 5: Commit** `feat(accounts): profile setup gate and language cutover`

### Task 5: Docs

**Files:**
- Modify:
  - `docs/bel-architecture.md` (§9 P5 marked done; §5 note that profile writers are on Bel);
  - `docs/bel-onboarding-mapping.md` (03/03a status; claims-based identity);
  - `docs/identity-deployment.md` (037, hook env, 033 guard, revocation latency ≤ `JWT_EXP`);
  - `docs/production-deployment.md` (migrate 035–037 and hook env before deploying this version).

- [ ] **Step 1:** Update the docs above.
- [ ] **Step 2:** `clojure -M:fmt check` (only `src/bel/handler.clj` and `src/tover/shared/supabase.clj` differ); `clj-kondo --lint src dev .clj-kondo/hooks/datastar.clj` → 0 errors, 0 warnings.
- [ ] **Step 3: Commit** `docs: record P5 profile and token-claims identity`
