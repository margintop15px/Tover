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
}

// Test-only identity fixtures run against a new durable inventory store for each process.
const program = `
  (require '[tover.app :as app] '[tover.identity :as identity]
           '[tover.session-store :as session-store])
  (def identities (identity/fixtures))
  ${process.env.TOVER_BROWSER_MANAGEMENT_FIXTURES === 'true' ? `
  (let [state (:state identities)
        workspace-id (java.util.UUID/fromString "00000000-0000-4000-8000-000000000103")
        user (fn [id email] {:user-id (java.util.UUID/fromString id) :email email
                             :email-confirmed? true :password "tover-development"})
        users [(user "00000000-0000-4000-8000-000000000011" "zero@example.test")
               (user "00000000-0000-4000-8000-000000000012" "manager@example.test")
               (user "00000000-0000-4000-8000-000000000013" "admin@example.test")
               (user "00000000-0000-4000-8000-000000000014" "suspended-member-with-an-intentionally-long-email-address@example.test")
               (user "00000000-0000-4000-8000-000000000015" "invited@example.test")
               (user "00000000-0000-4000-8000-000000000016" "native-en@example.test")
               (user "00000000-0000-4000-8000-000000000017" "native-ru@example.test")]
        [zero manager admin suspended invited] (map :user-id users)
        membership (fn [role status] {:role role :status status :created-at "2026-01-01T00:00:00Z"})
        invitation (fn [id email role status] {:id (java.util.UUID/fromString id)
                     :workspace-id workspace-id :email email :role role :status status
                     :created-at "2026-01-01T00:00:00Z"
                     :expires-at (if (= status :pending)
                                   (str (.plusSeconds (java.time.Instant/now) 604800))
                                   "2026-01-08T00:00:00Z")})
        invites [(invitation "00000000-0000-4000-8000-000000000201" "pending@example.test" :member :pending)
                 (invitation "00000000-0000-4000-8000-000000000202" "expired@example.test" :admin :expired)]]
    (swap! state (fn [value] (-> value
                    (update :users into (map (juxt :user-id clojure.core/identity) users))
                    (assoc-in [:users manager :display-name] "Alexandra Workspace Manager With A Long Name")
                    (assoc-in [:workspaces workspace-id] {:name "Management workspace"})
                    (assoc-in [:memberships workspace-id]
                      {manager (membership :owner :active) admin (membership :admin :active)
                       suspended (membership :member :suspended) invited (membership :member :invited)})
                    (update :invitations into (map (juxt :id clojure.core/identity) invites))))))
  ` : ''}
  (def system (app/start! (app/system-config ${port} ${JSON.stringify(join(project, 'inventory'))}
                           {:auth (:auth identities) :workspaces (:workspaces identities)
                            :session-store (session-store/memory-store)
                            :origin "http://127.0.0.1:${port}" :development? true})))
  (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable #(app/stop! system)))
  @(promise)
`;
const args = ['-Sdeps', '{:paths ["src" "resources" "dev"]}'];
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
