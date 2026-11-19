import { spawn } from 'node:child_process';
import { copyFileSync, mkdtempSync, readFileSync, rmSync, symlinkSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../../', import.meta.url));
const port = Number(process.env.TOVER_IDENTITY_BROWSER_PORT || 33310);
if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('Invalid browser test port');
const local = Object.fromEntries(readFileSync(join(root, 'deployment/local-identity/.env'), 'utf8')
  .split('\n').filter(line => line && !line.startsWith('#')).map(line => {
    const offset = line.indexOf('=');
    return [line.slice(0, offset), line.slice(offset + 1)];
  }));
const project = mkdtempSync(join(tmpdir(), 'tover-real-identity-browser-'));
copyFileSync(join(root, 'deps.edn'), join(project, 'deps.edn'));
for (const path of ['src', 'resources']) symlinkSync(join(root, path), join(project, path), 'dir');
const api = `http://127.0.0.1:${local.IDENTITY_API_PORT || 58000}`;
const env = {
  ...process.env,
  TOVER_AUTH_URL: `${api}/auth/v1`,
  TOVER_API_KEY: local.ANON_KEY, TOVER_AUTH_ADMIN_KEY: local.SERVICE_ROLE_KEY,
  TOVER_ORIGIN: `http://127.0.0.1:${port}`,
  TOVER_JDBC_URL: `jdbc:postgresql://127.0.0.1:${local.IDENTITY_DB_PORT || 55432}/postgres`,
  TOVER_DB_USER: 'tover_app', TOVER_DB_PASSWORD: local.TO_DEV_APP_PASSWORD,
  TOVER_JOURNAL_DB_USER: 'tover_journal', TOVER_JOURNAL_DB_PASSWORD: local.TO_DEV_JOURNAL_PASSWORD,
  TOVER_SESSION_KEY: local.SESSION_ENCRYPTION_KEY,
  // Invitation email goes to the profile's Mailpit, which identity.spec reads.
  TOVER_SMTP_HOST: '127.0.0.1', TOVER_SMTP_PORT: local.IDENTITY_SMTP_PORT || '51025',
  TOVER_SMTP_FROM: 'invitations@tover.test', TOVER_SMTP_STARTTLS: 'false',
  TOVER_PORT: String(port), TOVER_INVENTORY_PATH: join(project, 'inventory'),
};
const program = `(require '[tover.app :as app] '[bel.system :as system])
  (let [system (system/start! (app/config {:profile :dev}))]
    (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable #(system/stop! system)))
    @(promise))`;
const child = spawn('clojure', ['-M', '-e', program], { cwd: project, env, stdio: 'inherit' });
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => child.kill(signal));
child.once('error', () => {
  rmSync(project, { recursive: true, force: true });
  process.exit(1);
});
child.once('exit', code => {
  rmSync(project, { recursive: true, force: true });
  process.exit(code ?? 1);
});
