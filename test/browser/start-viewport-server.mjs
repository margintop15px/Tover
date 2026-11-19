import { spawn } from 'node:child_process';
import { copyFileSync, mkdirSync, mkdtempSync, rmSync, symlinkSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const port = Number(process.argv[2]);
if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('Usage: node start-viewport-server.mjs PORT');
const root = fileURLToPath(new URL('../../', import.meta.url));
const project = mkdtempSync(join(tmpdir(), 'tover-viewport-browser-'));
copyFileSync(join(root, 'deps.edn'), join(project, 'deps.edn'));
for (const path of ['src', 'resources']) symlinkSync(join(root, path), join(project, path), 'dir');
mkdirSync(join(project, 'dev'));
symlinkSync(join(root, 'dev', 'tover'), join(project, 'dev', 'tover'), 'dir');
const program = `
  (require '[tover.app :as app] '[bel.system :as system] '[tover.fixtures :as fixtures] '[tover.viewport-fixture :as fixture] '[tover.shared.rdf :as rdf])
  (def services (let [services (assoc (fixtures/system) :origin "http://127.0.0.1:${port}" :development? true)]
                  (assoc services :bel/system (fixtures/bel-system services))))
  (def repository (rdf/native-store ${JSON.stringify(join(project, 'inventory'))}))
  (fixture/seed! repository)
  (rdf/close! repository)
  (def system (system/start! (app/system-config ${port} ${JSON.stringify(join(project, 'inventory'))}
                           (select-keys services [:provider :session-store :bel/system :origin :development?]))))
  (alter-var-root #'tover.inventory.actors/labels (constantly (fixtures/actor-labels (:state services))))
  (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable #(system/stop! system)))
  @(promise)
`;
const child = spawn('clojure', ['-Sdeps', '{:paths ["src" "resources" "dev"]}', '-M', '-e', program], { cwd: project, stdio: 'inherit' });
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => child.kill(signal));
child.once('error', error => { console.error(error); rmSync(project, { recursive: true, force: true }); process.exit(1); });
child.once('exit', (code, signal) => { rmSync(project, { recursive: true, force: true }); process.exit(code ?? (signal ? 1 : 0)); });
