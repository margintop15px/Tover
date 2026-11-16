import { spawn } from 'node:child_process';
import { copyFileSync, mkdirSync, mkdtempSync, rmSync, symlinkSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const [mode, portText] = process.argv.slice(2);
const port = Number(portText);
if (!['production', 'development'].includes(mode) || !Number.isInteger(port) || port < 1 || port > 65535) {
  throw new Error('Usage: node start-server.mjs production|development PORT');
}

const root = fileURLToPath(new URL('../../', import.meta.url));
const project = mkdtempSync(join(tmpdir(), `tover-browser-${mode}-`));
copyFileSync(join(root, 'deps.edn'), join(project, 'deps.edn'));
for (const path of ['src', 'resources']) {
  symlinkSync(join(root, path), join(project, path), 'dir');
}
{
  // Exclude user.clj so the smoke server does not start the interactive dev lifecycle.
  mkdirSync(join(project, 'dev'));
  symlinkSync(join(root, 'dev', 'tover'), join(project, 'dev', 'tover'), 'dir');
  // Only the in-memory invitation coeffects come from the test tree.
  mkdirSync(join(project, 'test', 'tover'), { recursive: true });
  symlinkSync(join(root, 'test', 'tover', 'invitations'), join(project, 'test', 'tover', 'invitations'), 'dir');
}

// Test-only offline identity fixtures (tover.fixtures) run against a new durable inventory store per process.
const origin = `http://127.0.0.1:${port}`;
const program = `
  (require '[tover.app :as app] '[tover.fixtures :as fixtures])
  (def services (let [services (assoc (fixtures/system) :origin ${JSON.stringify(origin)})]
                  (assoc services :bel/system (fixtures/bel-system services))))
  ${process.env.TOVER_BROWSER_MANAGEMENT_FIXTURES === 'true' ? `
  (let [at (java.time.Instant/parse "2026-01-01T00:00:00Z")
        id #(java.util.UUID/fromString %)
        workspace-id (id "00000000-0000-4000-8000-000000000103")
        ;; Users who sign in have names, so profile setup does not stop them; the others show only their email.
        users [["00000000-0000-4000-8000-000000000011" "zero@example.test" "Zero"]
               ["00000000-0000-4000-8000-000000000012" "manager@example.test" "Alexandra Workspace Manager With A Long Name"]
               ["00000000-0000-4000-8000-000000000013" "admin@example.test" "Ada Admin"]
               ["00000000-0000-4000-8000-000000000014" "suspended-member-with-an-intentionally-long-email-address@example.test" nil]
               ["00000000-0000-4000-8000-000000000015" "invited@example.test" nil]
               ["00000000-0000-4000-8000-000000000016" "native-en@example.test" "Native EN"]
               ["00000000-0000-4000-8000-000000000017" "native-ru@example.test" "Native RU"]]
        memberships [["00000000-0000-4000-8000-000000000012" "owner" "active"]
                     ["00000000-0000-4000-8000-000000000013" "admin" "active"]
                     ["00000000-0000-4000-8000-000000000014" "member" "suspended"]
                     ["00000000-0000-4000-8000-000000000015" "member" "invited"]]
        invitation (fn [invite-id email role status expires-at]
                     [(id invite-id) {:id (id invite-id) :organization_id workspace-id :email email :role_id role
                                      :status "pending" :created_at at :expires_at expires-at
                                      :invited_by (id "00000000-0000-4000-8000-000000000012")}])]
    (swap! (:state services) update :db
           (fn [db]
             (-> db
                 (update :auth.users into (for [[user email] users]
                                            [(id user) {:id (id user) :email email :email_confirmed_at at :password fixtures/password}]))
                 (update :public.profiles into (for [[user _ name] users]
                                                 [(id user) (cond-> {:user_id (id user) :name_revision (if name 1 0) :language_revision 0}
                                                              name (assoc :display_name name))]))
                 (assoc-in [:public.organizations workspace-id]
                           {:id workspace-id :name "Management workspace" :created_by (id "00000000-0000-4000-8000-000000000012") :created_at at})
                 (update :public.organization_memberships (fnil into {})
                         (for [[user role status] memberships
                               :let [membership (java.util.UUID/nameUUIDFromBytes (.getBytes (str workspace-id user)))]]
                           [membership {:id membership :organization_id workspace-id :user_id (id user) :role_id role
                                        :status status :created_at at}]))
                 (update :public.organization_invites (fnil into {})
                         [(invitation "00000000-0000-4000-8000-000000000201" "pending@example.test" "member" "pending"
                                      (.plusSeconds (java.time.Instant/now) 604800))
                          (invitation "00000000-0000-4000-8000-000000000202" "expired@example.test" "admin" "pending"
                                      (java.time.Instant/parse "2026-01-08T00:00:00Z"))])))))
  ` : ''}
  (def system (app/start! (app/system-config ${port} ${JSON.stringify(join(project, 'inventory'))}
                           (select-keys services [:provider :session-store :bel/system :origin :development?]))))
  ;; The team and invitation reads are SQL coeffects; serve them from the same in-memory fixture state.
  (require '[bel.runtime :as runtime] '[tover.invitations.fixtures :as invitations])
  (doseq [[id coeffect] invitations/coeffects]
    (.addMethod ^clojure.lang.MultiFn runtime/coeffect id (fn [_ context input] (coeffect context input))))
  (alter-var-root #'tover.inventory.actors/labels (constantly (fixtures/actor-labels (:state services))))
  (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable #(app/stop! system)))
  @(promise)
`;
const args = ['-Sdeps', '{:paths ["src" "resources" "dev" "test"]}'];
const child = spawn('clojure', [...args, '-M', '-e', program], { cwd: project, stdio: 'inherit' });

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => child.kill(signal));
}
child.once('error', error => {
  console.error(error);
  rmSync(project, { recursive: true, force: true });
  process.exit(1);
});
child.once('exit', (code, signal) => {
  rmSync(project, { recursive: true, force: true });
  process.exit(code ?? (signal ? 1 : 0));
});
