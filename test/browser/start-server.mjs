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
  (require '[tover.app :as app] '[bel.system :as system] '[tover.fixtures :as fixtures])
  ;; Bel catalogue and inventory records live in their own RDF store, as tover.app composes it.
  (require '[bel-rdf.adapter :as bel-rdf] '[tover.catalogues.persistence :as catalogues])
  (def catalogue-repo (app/repo {:path ${JSON.stringify(join(project, 'catalogue'))}}))
  (def services (let [services (assoc (fixtures/system) :origin ${JSON.stringify(origin)})]
                  (assoc services :bel/system (assoc-in (fixtures/bel-system services) [:stores :catalogue]
                                                        (bel-rdf/store {:repo catalogue-repo :graphs catalogues/graphs
                                                                        :guard catalogues/legacy-guard})))))
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
        ;; One workspace per locale for the member actions (board 34–39): owner, admin, member and heir.
        actors (for [[n locale] [[1 "en"] [2 "ru"]]
                     [m person] [[1 "owner"] [2 "admin"] [3 "member"] [4 "heir"]]]
                 {:workspace-id (id (format "00000000-0000-4000-8000-00000000030%d" n))
                  :workspace    (str "Actions " locale)
                  :user         (format "00000000-0000-4000-8000-0000000003%d%d" n m)
                  :email        (str "actions-" person "-" locale "@example.test")
                  :name         (str "Actions " person " " locale)
                  :role         (case person "owner" "owner" "admin" "admin" "member")})
        users (into users (map (juxt :user :email :name)) actors)
        memberships [["00000000-0000-4000-8000-000000000012" "owner" "active"]
                     ["00000000-0000-4000-8000-000000000013" "admin" "active"]
                     ["00000000-0000-4000-8000-000000000014" "member" "suspended"]
                     ["00000000-0000-4000-8000-000000000015" "member" "invited"]]
        invitation (fn [invite-id email role status expires-at]
                     [(id invite-id) {:invitation_id (id invite-id) :workspace_id workspace-id :email email :role role
                                      :status "pending" :created_at at :expires_at expires-at
                                      :invited_by (id "00000000-0000-4000-8000-000000000012")}])]
    (swap! (:state services) update :db
           (fn [db]
             (-> db
                 (update :auth.users into (for [[user email] users]
                                            [(id user) {:id (id user) :email email :email_confirmed_at at :password fixtures/password}]))
                 (update :profiles into (for [[user _ name] users]
                                                 [(id user) (cond-> {:user_id (id user) :name_revision (if name 1 0) :language_revision 0}
                                                              name (assoc :display_name name))]))
                 (assoc-in [:workspaces workspace-id]
                           {:workspace_id workspace-id :name "Management workspace" :created_by (id "00000000-0000-4000-8000-000000000012") :created_at at})
                 (update :memberships (fnil into {})
                         (for [[user role status] memberships
                               :let [membership (java.util.UUID/nameUUIDFromBytes (.getBytes (str workspace-id user)))]]
                           [membership {:membership_id membership :workspace_id workspace-id :user_id (id user) :role role
                                        :status status :created_at at}]))
                 (update :workspaces into (for [{:keys [workspace-id workspace]} actors]
                                            [workspace-id {:workspace_id workspace-id :name workspace :created_at at
                                                           :created_by (id (:user (first actors)))}]))
                 (update :memberships into (for [{:keys [workspace-id user role]} actors
                                                 :let [membership (java.util.UUID/nameUUIDFromBytes (.getBytes (str workspace-id user)))]]
                                             [membership {:membership_id membership :workspace_id workspace-id :user_id (id user)
                                                          :role role :status "active" :revision 1 :created_at at}]))
                 (update :invitations (fnil into {})
                         [(invitation "00000000-0000-4000-8000-000000000201" "pending@example.test" "member" "pending"
                                      (.plusSeconds (java.time.Instant/now) 604800))
                          (invitation "00000000-0000-4000-8000-000000000202" "expired@example.test" "admin" "pending"
                                      (java.time.Instant/parse "2026-01-08T00:00:00Z"))])))))
  ` : ''}
  (def system (system/start! (app/system-config ${port} ${JSON.stringify(join(project, 'inventory'))}
                           (select-keys services [:provider :session-store :limits :bel/system :origin :development?]))))
  ${process.env.TOVER_BROWSER_MARKETPLACE_FIXTURES === 'true' ? `
  ;; Board 42: an Ozon connection of the development workspace with four listings; three have a single fitting item.
  (require '[bel.core :as bel] '[bel.runtime :as runtime] '[bel.store :as store]
           '[tover.marketplace.connections.model :as connections] '[tover.marketplace.connector :as connector]
           '[tover.marketplace.sync.observations :as observations])
  (let [at  "2026-11-20T10:00:00Z"
        ctx (assoc (runtime/prepare-context {:bel/system   (:bel/system services) :repo catalogue-repo
                                             :workspace-id fixtures/workspace-id :user-id fixtures/owner-id
                                             :memberships  [{:workspace-id fixtures/workspace-id :role :owner}]})
                   :bel/definitions (bel/operations))
        put (fn [type data] (store/with-store ctx :catalogue :serializable #(runtime/apply-event! % {:type type :data data})))
        listing (fn [source-id name barcode]
                  (let [id (str "browser-ozon:" source-id)]
                    {:external-product-id id :entity-id id :connection-id "browser-ozon" :source-id source-id :revision 1
                     :payload-hash (apply str (repeat 64 "a")) :adapter-version "test" :observed-at at :last-observed-at at
                     :source-revision-id (str id "#1") :name name :archived false
                     :identifiers (observations/write-json (if barcode [{:type "barcode" :value barcode :active true}] []))
                     :facts (observations/write-json {:base {:name name}})}))]
    (put ::connections/marketplace-account-connected
         {:connection-id "browser-ozon" :workspace-id (str fixtures/workspace-id) :provider "ozon" :provider-account-id "777"
          :account-label "Browser shop" :secret-ref "browser-secret" :account-currency "RUB"
          :readable-domains (connections/encode-domains connector/domains) :sync-state "active" :credentials-state "valid"
          :connected-by (str fixtures/owner-id) :connected-at at})
    (doseq [[n name] [[1 "Kettle"] [2 "Teapot"] [3 "Saucer"]]
            :let [product (str "browser-product-" n) item (str "browser-item-" n) barcode (str "460000000010" n)]]
      (put :tover.catalogues.products.model/product-registered {:product-id product :name name :status "active" :revision 1})
      (put :tover.inventory.stock-items.model/stock-item-registered
           {:stock-item-id item :product-id product :condition "regular" :status "active" :revision 1})
      (put :tover.inventory.stock-items.model/stock-item-barcode-assigned
           {:barcode-id (str item "|" barcode) :stock-item-id item :barcode barcode})
      (put :tover.marketplace.sync.model/external-product-observed (listing (str "10" n) (str name " (Ozon)") barcode)))
    (put :tover.marketplace.sync.model/external-product-observed (listing "104" "Gift card" nil))
    ;; Board 43: two FBO warehouses and one seller warehouse, all unmatched.
    (doseq [[type source-id name] [["fbo" "201" "Khorugvino"] ["fbo" "202" "Tver"] ["fbs" "203" "Seller stock"]]
            :let [id (observations/warehouse-id "browser-ozon" type source-id)]]
      (put :tover.marketplace.sync.model/external-warehouse-observed
           {:external-warehouse-id id :entity-id id :connection-id "browser-ozon" :source-id source-id :revision 1
            :payload-hash (apply str (repeat 64 "b")) :adapter-version "test" :observed-at at :last-observed-at at
            :source-revision-id (str id "#1") :name name :warehouse-type type :active true
            :facts (observations/write-json {:name name})})))
  ` : ''}
  ;; The team and invitation reads are SQL coeffects; serve them from the same in-memory fixture state.
  (require '[bel.runtime :as runtime] '[tover.invitations.fixtures :as invitations])
  (doseq [[id coeffect] invitations/coeffects]
    (.addMethod ^clojure.lang.MultiFn runtime/coeffect id (fn [_ context input] (coeffect context input))))
  (alter-var-root #'tover.inventory.actors/labels (constantly (fixtures/actor-labels (:state services))))
  (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable #(system/stop! system)))
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
