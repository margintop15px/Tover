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

// The smoke fixture distinguishes recorded stock from the visual development examples.
const examples = mode === 'development';
const program = `
  (require '[tover.app :as app] '[tover.stock.store :as store]
           '[tover.identity :as identity] '[tover.session-store :as session-store])
  (def identities (identity/fixtures))
  ${examples ? "(require '[tover.operations.examples :as examples])" : ''}
  (def configuration
    (cond-> (app/system-config ${port} ${JSON.stringify(join(project, 'inventory'))}
                               {:auth (:auth identities) :workspaces (:workspaces identities)
                                :session-store (session-store/memory-store)
                                :origin "http://127.0.0.1:${port}" :development? true})
      ${examples} (assoc-in [::app/handler :operation-examples]
                            ${examples ? '{:rows examples/rows :entries examples/entries}' : '{}'})))
  (def system (app/start! configuration))
  ${examples ? `(store/declare-opening! (::app/repo system) {:workspace-id (:workspace-id identities)}
                 {:id "smoke-recorded" :name "Recorded smoke stock" :article "SMOKE-STORED"
                  :quantity 11 :unit "pcs" :location "Smoke shelf"})` : ''}
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
