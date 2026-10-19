# Historical Auth configuration

This is a copied historical runbook, not instructions for the replacement runtime. All old source paths and commands below belong to commit `9f799586a62226bc62643e5dedc54d76a0176359`. No command here was executed during extraction. Configuration may have drifted; production changes require their own review and authorization.

Source: `baseline:README.md:130-162` (verbatim excerpt).

### 2. Configure Auth URLs in Supabase

In Auth settings:
- Site URL: `http://localhost:3000`
- Additional redirect URLs: `http://localhost:3000/auth/callback`

For a deployed app, add its exact origin plus `/auth/callback` as well, and set
the Site URL to that app's origin. Password recovery also uses
`/auth/callback?next=/reset-password`; allow that URL too.

Team invitation emails (`type=invite`) establish a session and open the existing
password setup screen before entering the app. If the email already belongs to
a confirmed account, the invitation stays pending and a fresh magic sign-in link
is sent instead (`shouldCreateUser: false`). These links use the implicit flow so
the recipient can open them in another browser. The existing Magic Link email
template must contain `{{ .ConfirmationURL }}`. After signing in, the verified-email
invitation acceptance adds the workspace to their switcher. No account deletion
or password reset is required.

Signup confirmation emails
(`type=signup`, with a `pkce_` token) are a separate flow: automatic sign-in
requires the same browser and origin where signup started. After confirming in
a different browser, the user can log in with the password chosen during signup.

Migration `029_secure_workspace_invitations.sql` prevents signup metadata from
granting membership in an existing workspace. After Auth verifies the user's
email, the existing `accept_my_organization_invites()` call accepts only pending,
unexpired invitations matching that email and takes the workspace and role from
the invitation record. The migration does not remove existing memberships;
review any suspicious earlier grants separately.

Run the database regression test with `bash scripts/test-auth-security.sh`.
It requires Docker and uses a disposable PostgreSQL container without networking.

Source: `baseline:README.md:462-476` (verbatim security excerpt).

## Security Notes

- Service role key is used only in server code for privileged auth admin actions.
- App data access is enforced via Postgres RLS and organization membership policies.
- Do not call privileged operations from client-side code.
