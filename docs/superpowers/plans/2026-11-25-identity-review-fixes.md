# Identity review fixes (board chapters 01–02c) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Tover limits confirmation, recovery and sign-in requests itself without revealing accounts, passwords can be shown while typing, and Sign out is reachable from the profile and returns to an invitation.

**Architecture:** A new namespace `tover.identity.limits` holds both limits behind one protocol: PostgreSQL in the app, an atom in fixtures, sharing one pure decision function. Signup, resend and recovery validate, take the email slot, answer, then call the provider in the background. Sign In reserves an attempt before the provider call and settles it afterwards. Limits are not Bel commands: the visitor is anonymous, so no user scope exists, and nothing here is business history.

**Tech Stack:** Clojure, Bel, next.jdbc, PostgreSQL, Datastar pages with plain JS, Kaocha, Playwright.

**Spec:** prooph board chapters 01, 01a, 02, 02a, 02b and the first slice of 06. Read the slices below with `get_chapter` and the details of `Resend Confirmation Email`, `Register Account`, `Request Password Recovery`, `Sign In`, `Registration Requirements`. The board wins over this plan.

## Slices to set `planned`

| Chapter | Slice | Was | Task |
| --- | --- | --- | --- |
| 01 | Start signup | reviewed, reopened 2026-11-25 | 2, 5 |
| 01 | Register an unconfirmed account | blocked | 3 |
| 01a | Request another confirmation message | blocked | 3 |
| 01a | Check email or use another recovery option | reviewed, reopened | 3 |
| 02 | Return to Tover | reviewed, reopened | 5 |
| 02 | Establish a usable sign-in | blocked | 4 |
| 02a | Request a password recovery message | blocked | 3 |
| 02a | Check for the recovery email | reviewed, reopened | 3 |
| 02a | Choose a new password from the recovery link | reviewed, reopened | 2, 5 |
| 02b | Choose to end the current sign-in | blocked | 6 |
| 02b | Continue after sign-out | blocked | 6 |
| 06 | Review the exact workspace and role | blocked | 6 |

02c needs nothing. The 06 slice is outside 01–02c, but the 02b sign-out examples cannot be met without it. Implementation starts only with slices that are `planned`; set each to `in-progress`, then `ready`.

## Decided 2026-11-25

- Show control on every password field.
- IPv6 counts by /64, IPv4 exactly.
- Provider email frequency goes from 60s to 40s, so a retry at 60 seconds is not silently dropped.
- Signup, resend and recovery answer before the provider does; outages and timing look the same for every account.
- Passwords: 6 characters to 72 UTF-8 bytes.

Calls made while planning, written on the board; check them when approving:

- An attempt still waiting for the provider counts, for at most one minute. Otherwise parallel guesses pass the limit.
- A disabled account answers like a wrong password and counts. Today the provider's `user_banned` code shows a different message.
- A successful sign-in lowers only its email-and-address count; the two counts of 100 keep those failures.
- When several limits are reached, the longest wait is shown.
- An unacceptable new password is refused before the recovery link is used.
- Profile that cannot be read still shows Sign Out.

Not covered: response time of Sign In itself (the provider checks a password hash only for known emails), and a limit on how many different emails one address may request.

## Approaches considered

- **PostgreSQL table with advisory locks (chosen).** Survives restarts and works across instances, as the board requires. No new dependency.
- **Counters in memory.** Smallest, but loses the count on restart and differs per instance.
- **Limits in Caddy or GoTrue.** Caddy cannot see the email without reading bodies and needs a plugin; GoTrue's limits are per account or per server address, which is the reported defect.

## Global Constraints

- REPL-first; run `clj-paren-repair` for delimiters; never `:reload-all`.
- Every board example of the slices above has a scenario test.
- New migration is `supabase/migrations/003_identity_limits.sql`, no `BEGIN`/`COMMIT`. Additive only.
- The running app deletes its own expired rows from the two new tables (over 15 minutes and over 60 seconds old). **Approving this plan approves that routine deletion.** Nothing else is deleted.
- Emails are stored only as SHA-256 of the normalized email. No passwords in any row, log or message.
- Text goes through `i18n/t` with en and ru in `resources/i18n/messages.json`.
- Malli open maps; props-map components.

## Review Focus

1. The app behind Caddy sees one address for everyone, so 100 failures lock out all users: Task 4 tests the forwarded address, Task 7 checks it on the deployment.
2. A client sends its own `X-Forwarded-For`: only the last entry, written by our proxy, counts (Task 4).
3. The provider hangs or the app stops during a sign-in: the pending attempt stops counting after one minute (Task 1).
4. Email with different case or spaces: same limit (Task 1).
5. Cyrillic password of 37 letters (74 bytes): field error, not a silent non-registration (Task 2).

---

### Task 1: Limits store

**Files:**
- Create: `supabase/migrations/003_identity_limits.sql`, `src/tover/identity/limits.clj`, `test/tover/identity/limits_test.clj`
- Modify: `dev/tover/fixtures.clj`, `src/tover/app.clj`, `resources/tover/config.edn`, `test/identity_integration.clj`

**Interfaces (produced):**

```clojure
(defprotocol Limits
  (-start-email! [limits purpose email-key])        ; purpose :confirmation | :recovery -> nil | remaining seconds
  (-start-attempt! [limits email-key address])      ; -> {:attempt-id uuid} | {:retry-after seconds}
  (-settle-attempt! [limits attempt-id outcome])    ; outcome :failed | :succeeded | :discarded
  (-clear-email! [limits email-key]))
(defn email-key [email])                            ; SHA-256 hex of trimmed, Locale/ROOT lower-cased email
(defn retry-after [now rows email-key address])     ; pure: nil | seconds, rounded up
(defn jdbc-limits [{:keys [datasource]}])
```

Tables, both with row level security and one `FOR ALL TO tover_app` policy like `sessions_runtime`:

```sql
app_private.identity_email_limits (purpose text CHECK (purpose IN ('confirmation','recovery')),
  email_key text, started_at timestamptz, PRIMARY KEY (purpose, email_key))
app_private.identity_attempts (attempt_id uuid PRIMARY KEY, email_key text, address text, at timestamptz,
  status text CHECK (status IN ('pending','failed')), pair_cleared boolean DEFAULT false,
  email_cleared boolean DEFAULT false)   -- indexes: (email_key, at), (address, at)
```

Rows count when `failed` within 15 minutes or `pending` within 1 minute. Pair count (10) ignores `pair_cleared` and `email_cleared`; email count (100) ignores `email_cleared`; address count (100) ignores neither. For each count at its limit, the wait is that of the limit-th newest row (`at` plus its window); the result is the longest.

`:succeeded` deletes the attempt and sets `pair_cleared` on its pair. `:discarded` deletes it. `:failed` sets the status. `-clear-email!` sets both flags for the email.

JDBC: `-start-email!` is one `INSERT … ON CONFLICT DO UPDATE … WHERE started_at <= now() - interval '60 seconds' RETURNING`; no row means too soon, then read the remaining seconds. `-start-attempt!` is one transaction: `pg_advisory_xact_lock(hashtextextended(?, 0))` for the email and the address in sorted key order, delete expired rows, read rows, decide with `retry-after`, insert `pending`. Times are the database's.

- [ ] Tests over the fixture store (clock in the state atom): `the-eleventh-attempt-from-one-place-waits`, `the-hundred-and-first-attempt-from-one-address-waits`, `one-email-from-many-addresses-waits`, `a-success-clears-only-its-pair`, `a-cleared-email-keeps-the-address-count`, `a-pending-attempt-counts-for-one-minute`, `a-refused-attempt-does-not-extend-the-wait`, `case-and-spaces-share-a-limit`, `purposes-are-counted-apart`, `an-email-may-ask-again-after-sixty-seconds`. Run, see them fail.
- [ ] Implement `limits.clj` and the fixture record; add `:limits` to `fixtures/system`, its `bel-system` resources, `HandlerOptions`, the handler context, `system-config`, `config.edn` and an Integrant key `:tover.app/limits`. Run `clojure -M:dev:test --focus tover.identity.limits-test`: pass.
- [ ] `identity_integration.clj` against local Postgres: 11 concurrent `-start-attempt!` give 10 ids; two concurrent `-start-email!` give one nil; a second store instance sees the same counts; all as `tover_app`. Run `scripts/test-identity-integration.sh`: pass.
- [ ] Commit.

### Task 2: Email and password input rules

**Files:** Create `src/tover/identity/input.clj`, `test/tover/identity/input_test.clj`. Modify `signup/web.clj`, `recovery/web.clj`; tests `signup_test.clj`, `recovery_test.clj`.

**Produces:** `(input/email-error s)`, `(input/new-password-error s)` -> nil or translated message. Email: one `@`, non-blank parts, no whitespace or control characters, at most 255 characters. Password: at least 6 code points, at most 72 UTF-8 bytes.

- [ ] Tests: 5 and 6 characters; 36 and 37 Cyrillic letters; `a@b`, `a`, `a b@c.d`; signup with a 5-character or 37-Cyrillic password answers 422 on the form, email kept, password absent, provider not called; `an-unacceptable-new-password-keeps-the-recovery-link` (422, then the same link works); on `/auth/password` a refused password retains no reset attempt.
- [ ] Implement; check in `register`, `reset-password` (before `::verify`) and `set-password` (before `change-password!`). Pass; commit.

### Task 3: Email limit and neutral answers

**Files:** Modify `provider.clj`, `signup/web.clj`, `resend/web.clj`, `recovery/web.clj`, `test/tover/support.clj`, `signup_test.clj`, `recovery_test.clj`, `deployment/production/compose.yaml`, `docs/identity-deployment.md`.

**Produces:** `(provider/call-later! context id input)` -> nil. Virtual thread behind a `Semaphore` of 16; logs only the failure code. `support/with-application` redefines it to run at once, swallowing failures the same way.

- [ ] Tests, each for unknown, unconfirmed and confirmed emails: first answers are equal apart from the email, second answers within 60 seconds are equal with status 429 and the remaining seconds, for signup (on Check Your Email), resend and recovery; signup and resend share a slot; recovery is apart; provider `:rate-limited`, 503, timeout and invalid-input all give the first answer; `the-answer-does-not-wait-for-the-provider` (real `call-later!`, provider blocked on a promise); two concurrent resends call the provider once; refused input starts no limit. Replace `a-throttled-resend-…` and `a-lost-registration-response-…`.
- [ ] Implement: order is input check, `-start-email!`, `call-later!`, answer. Remove the immediate sign-in branch and `:neutral`/`:unknown` handling from `register` (keep `model/outcome`; other screens use it).
- [ ] `GOTRUE_SMTP_MAX_FREQUENCY: 40s` in production compose; note both limits in `identity-deployment.md`. Check the pinned GoTrue image for `GOTRUE_RATE_LIMIT_EMAIL_SENT`: if its default is a global hourly cap that applies to custom SMTP, report it to the user, since neutral answers would hide it. Do not choose a value.
- [ ] Pass; commit.

### Task 4: Sign In limit

**Files:** Create `src/tover/shared/web/address.clj`, `test/tover/shared/web/address_test.clj`. Modify `sign_in/{model,web}.clj`, `provider.clj`, `reset_completion/web.clj`, `app.clj`, `config.edn`, `deployment/production/compose.yaml`; tests `sign_in_test.clj`, `recovery_test.clj`.

**Produces:** `(address/visitor request trusted-proxy?)` -> string. With `trusted-proxy?`, the last `X-Forwarded-For` entry when it is an IP literal, else the socket address; without, always the socket address; `"unknown"` when absent. Literals only, never a DNS lookup. IPv4-mapped IPv6 counts as IPv4; IPv6 becomes its `/64`. Config `:trusted-proxy? #tover/flag ["TOVER_TRUSTED_PROXY" false]`; production compose sets `"true"`.

- [ ] Address tests: two entries take the last; spoofed first entry ignored; header ignored when untrusted; `dead.beef` rejected; two addresses of one `/64` equal; `::ffff:192.0.2.1` equals `192.0.2.1`.
- [ ] Scenario tests named after the nine examples of `Establish a usable sign-in`, plus: a disabled account (`user_banned`) answers like a wrong password and counts; a refused attempt never reaches the provider; a provider 429, 503 or timeout counts nothing; an accepted credential whose sign-in cannot be recorded counts nothing; a reset finished by `resume!` clears the email. Refusal is status 429 on Sign In with the email kept, the remaining time, and the Forgot password link. Replace `repeated-attempts-are-limited-…`.
- [ ] Implement in `sign-in`: `-start-attempt!`, provider, `-settle-attempt!` in every path: established is `:succeeded`; outcomes `:rejected` and provider `:invalid` are `:failed` and show `Invalid email or password.`; everything else is `:discarded`. A blank email or password is refused before an attempt starts. Map `user_banned` to `:invalid-credentials`. `finish!` calls `-clear-email!` with the row's account email after the command, so its retry covers it.
- [ ] Pass; commit.

### Task 5: Show password control

**Files:** Modify `shared/ui/forms.clj` (`field`), `recovery/web.clj`, `resources/public/{identity.js,app.css}`, `messages.json`; test `signup_test.clj`, `test/browser/identity.spec.mjs`.

Markup: the password input sits in `span.password-field` followed by `button[type=button].password-toggle[hidden]`, `aria-pressed="false"`, `aria-label` "Show password", inline eye SVG. The script unhides it and, by delegation so Datastar patches keep working, switches the input type, `aria-pressed` and icon. The label text stays the same; pressed state says the rest. Inputs get `autocomplete`: `current-password` on Sign In, `new-password` elsewhere. `label` must not wrap the button: use `for`/`id`.

- [ ] Server test: the three pages contain one toggle per password input. Browser test: keyboard Enter on the toggle shows and hides, the URL and form are unchanged, after reload the type is `password`.
- [ ] Implement; check light, dark and 375px width; pass; commit.

### Task 6: Sign out from profile and invitation

**Files:** Modify `accounts/profile/web.clj`, `workspaces/select/web.clj`, `sign_out/web.clj`, `invitations/accept/web.clj`, `app.css`, `messages.json`; tests `profile_web_test.clj`, `sign_out_test.clj`, `invitations/web_test.clj`, `test/browser/identity.spec.mjs` (`logout` goes to `/account/profile`).

- Profile: `sign-out-form` after the profile form, or after Retry with the email from the identity; not nested in another form. Removed from the chooser.
- Invitation: the unavailable card uses `sign-out-form` with `:return-to (review-path id)` and says that signing out leads to Sign In and that the right account returns here.
- `public-destination` also accepts exactly `/invitations/<uuid>`. The signed-out redirect there goes through the access gate, which already keeps the destination and shows Sign In. No other code carries it.

- [ ] Tests named after the examples of both 02b slices (two on the first, the profile read failure included) and the four of 06; also `/invitations/<uuid>/joined`, `/invitations/x`, `/stock` and `//host` as `return-to` go to Sign In; signing out with an invitation, then signing up and confirming in the same browser, continues to it after profile setup.
- [ ] Implement; pass; commit.

### Task 7: Checks and release notes

- [ ] `clojure -M:dev:test`, `clojure -M:fmt check`, `clj-kondo --lint src dev .clj-kondo/hooks/datastar.clj`, `scripts/test-identity-integration.sh`, `scripts/test-auth-security.sh`, Playwright identity, i18n and management configs. All pass.
- [ ] Fix wording in `resend/web.clj`, `signup/web.clj` docstrings and add `limits`, `input` to `docs/bel-onboarding-mapping.md` shared pieces (location only).
- [ ] `docs/production-deployment.md`: migration 003 before the new app; recreate Auth for the 40s setting; after release, fail one sign-in from two networks and confirm `identity_attempts` holds two different public addresses, not a Docker gateway. Publish an AAAA record only after this also holds for IPv6. Rollback to the old app ignores the new tables.
- [ ] Board: slices to `ready`. Leave `docs/board-sync.edn` unless other chapters' changes since its instant are handled too.
