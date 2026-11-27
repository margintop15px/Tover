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
    (put :tover.marketplace.sync.model/external-product-observed
         (assoc (listing "104" "Gift card" nil) :identifiers (observations/write-json [{:type "sku" :value "104" :active true}])))
    ;; Board 45: one delivered FBS posting of the unmatched Gift card listing waits on the Operations page.
    (let [id "browser-ozon:fbs-posting:P1"]
      (put :tover.marketplace.sync.model/source-operation-observed
           {:source-operation-id id :entity-id id :connection-id "browser-ozon" :source-id "P1" :revision 1
            :payload-hash (apply str (repeat 64 "c")) :adapter-version "test" :observed-at at :last-observed-at at
            :source-revision-id (str id "#1") :facts (observations/write-json {}) :kind "fbs-posting" :status "delivered"
            :terminal true :occurred-at "2026-11-10T09:00:00Z" :from-warehouse "browser-ozon:fbs:203" :complete true
            :lines (observations/write-json [{:line-key "1" :identifiers [{:type "sku" :value "104"}] :quantity "2"}])}))
    ;; A Ladle listing confirmed, then suspended by the Mapping Guard: Reconfirm in the Suspended filter.
    (put :tover.catalogues.products.model/product-registered {:product-id "browser-product-5" :name "Ladle" :status "active" :revision 1})
    (put :tover.inventory.stock-items.model/stock-item-registered
         {:stock-item-id "browser-item-5" :product-id "browser-product-5" :condition "regular" :status "active" :revision 1})
    (put :tover.inventory.stock-items.model/stock-item-barcode-assigned
         {:barcode-id "browser-item-5|4600000001005" :stock-item-id "browser-item-5" :barcode "4600000001005"})
    (put :tover.marketplace.sync.model/external-product-observed (listing "105" "Ladle (Ozon)" "4600000001005"))
    (let [mapping {:external-product-id "browser-ozon:105" :connection-id "browser-ozon" :stock-item-id "browser-item-5"
                   :listing-revision 1 :item-revision 1 :identity-basis "{}" :changed-by (str fixtures/owner-id) :changed-at at}]
      (put :tover.marketplace.products.model/external-product-mapped
           (assoc mapping :mapping-revision-id "browser-ozon:105#1" :mapping-revision 1 :change "mapped" :state "confirmed"))
      (put :tover.marketplace.products.model/product-mapping-suspended
           (assoc mapping :mapping-revision-id "browser-ozon:105#2" :mapping-revision 2 :change "suspended" :state "suspended"
                          :changed-by "connector:browser-ozon"
                          :reasons (observations/write-json [{:rule "listing-identity-changed" :before "a" :after "b"}]))))
    ;; Board 43: two FBO warehouses and one seller warehouse, all unmatched.
    (doseq [[type source-id name] [["fbo" "201" "Khorugvino"] ["fbo" "202" "Tver"] ["fbs" "203" "Seller stock"]]
            :let [id (observations/warehouse-id "browser-ozon" type source-id)]]
      (put :tover.marketplace.sync.model/external-warehouse-observed
           {:external-warehouse-id id :entity-id id :connection-id "browser-ozon" :source-id source-id :revision 1
            :payload-hash (apply str (repeat 64 "b")) :adapter-version "test" :observed-at at :last-observed-at at
            :source-revision-id (str id "#1") :name name :warehouse-type type :active true
            :facts (observations/write-json {:name name})}))
    ;; Board 44: a Whisk listing and an FBO warehouse already mapped, every operation domain read once, and a
    ;; finished stock-by-warehouse pass that found 7 whisks there: one scope ready for the cutover.
    (put :tover.catalogues.warehouses.model/warehouse-registered {:warehouse-id "browser-cutover-hub" :name "Cutover hub" :status "active" :revision 1})
    (put :tover.catalogues.products.model/product-registered {:product-id "browser-product-6" :name "Whisk" :status "active" :revision 1})
    (put :tover.inventory.stock-items.model/stock-item-registered
         {:stock-item-id "browser-item-6" :product-id "browser-product-6" :unit "pcs" :condition "regular" :status "active" :revision 1})
    (put :tover.marketplace.sync.model/external-product-observed
         (assoc (listing "106" "Whisk (Ozon)" nil) :identifiers (observations/write-json [{:type "sku" :value "106" :active true}])))
    (put :tover.marketplace.products.model/external-product-mapped
         {:external-product-id "browser-ozon:106" :connection-id "browser-ozon" :stock-item-id "browser-item-6" :listing-revision 1
          :item-revision 1 :identity-basis (observations/write-json {:attributes {} :pack nil :unit "pcs" :variant-label nil})
          :changed-by (str fixtures/owner-id) :changed-at at :mapping-revision-id "browser-ozon:106#1" :mapping-revision 1 :change "mapped" :state "confirmed"})
    (let [hub (observations/warehouse-id "browser-ozon" "fbo" "204")
          domains ["fbs-postings" "fbo-postings" "returns" "rfbs-returns" "supplies" "removals"]
          earliest "2026-11-01T00:00:00Z"]
      (put :tover.marketplace.sync.model/external-warehouse-observed
           {:external-warehouse-id hub :entity-id hub :connection-id "browser-ozon" :source-id "204" :revision 1
            :payload-hash (apply str (repeat 64 "c")) :adapter-version "test" :observed-at at :last-observed-at at
            :source-revision-id (str hub "#1") :name "Cutover hub" :warehouse-type "fbo" :active true
            :facts (observations/write-json {:name "Cutover hub"})})
      (put :tover.marketplace.warehouses.model/external-warehouse-mapped
           {:external-warehouse-id hub :mapping-revision-id (str hub "#1") :connection-id "browser-ozon" :warehouse-type "fbo"
            :mapping-revision 1 :changed-by (str fixtures/owner-id) :changed-at at :change "mapped"
            :warehouse-id "browser-cutover-hub" :state "confirmed"})
      (put :tover.marketplace.sync.model/marketplace-sync-started
           {:run-id "browser-r0" :connection-id "browser-ozon" :trigger "member" :triggered-by (str fixtures/owner-id)
            :domains (clojure.string/join " " domains) :starting-positions "{}" :started-at earliest :run-state "open"})
      (doseq [domain domains]
        (put :tover.marketplace.sync.model/source-batch-recorded
             {:position-id (str "browser-ozon/" domain) :connection-id "browser-ozon" :domain domain :run-id "browser-r0"
              :cursor "" :batch-key "" :refetch-ids "[]" :finished true :found-listings 0 :found-warehouses 0
              :found-operations 0 :recorded-at earliest})
        (put :tover.marketplace.sync.model/source-domain-traversed
             {:run-id "browser-r0" :connection-id "browser-ozon" :domain domain :covered-until earliest :traversed-at earliest
              :position-id (str "browser-ozon/" domain) :finished-domains domain :first-covered-at earliest
              :pass-id (str "browser-r0/" domain)}))
      (put :tover.marketplace.sync.model/marketplace-sync-started
           {:run-id "browser-r1" :connection-id "browser-ozon" :trigger "member" :triggered-by (str fixtures/owner-id)
            :domains "stock-by-warehouse" :starting-positions "{}" :started-at at :run-state "open"})
      (put :tover.marketplace.sync.model/source-batch-recorded
           {:position-id "browser-ozon/stock-by-warehouse" :connection-id "browser-ozon" :domain "stock-by-warehouse"
            :run-id "browser-r1" :cursor "" :batch-key "" :refetch-ids "[]" :finished true :found-listings 1
            :found-warehouses 0 :found-operations 0 :recorded-at at})
      (put :tover.marketplace.sync.model/source-domain-traversed
           {:run-id "browser-r1" :connection-id "browser-ozon" :domain "stock-by-warehouse" :covered-until at :traversed-at at
            :position-id "browser-ozon/stock-by-warehouse" :finished-domains "stock-by-warehouse" :first-covered-at at
            :pass-id "browser-r1/stock-by-warehouse" :asked (observations/write-json ["106"])})
      (put :tover.marketplace.sync.model/source-stock-observed
           {:stock-id (str "browser-ozon:106|warehouse-stock|" hub "|browser-r1") :connection-id "browser-ozon"
            :run-id "browser-r1" :external-product-id "browser-ozon:106" :external-warehouse-id hub :source "warehouse-stock"
            :fulfilment-type "fbo" :present 7 :parts "[]" :observed-at at})
      ;; Board 45: a sale of 10 whisks from the hub after the cutover; once the baseline of 7 is accepted it is
      ;; proposed, 3 short, with its correction.
      (let [id "browser-ozon:fbo-posting:W1"]
        (put :tover.marketplace.sync.model/source-operation-observed
             {:source-operation-id id :entity-id id :connection-id "browser-ozon" :source-id "W1" :revision 1
              :payload-hash (apply str (repeat 64 "d")) :adapter-version "test" :observed-at at :last-observed-at at
              :source-revision-id (str id "#1") :facts (observations/write-json {}) :kind "fbo-posting" :status "delivered"
              :terminal true :occurred-at "2026-11-21T09:00:00Z" :from-warehouse hub :complete true
              :lines (observations/write-json [{:line-key "1" :identifiers [{:type "sku" :value "106"}] :quantity "10"}])})))
    ;; Board 45 slice 9: a sale of one whisk from a shelf outside the connection's warehouses, accepted automatically
    ;; at revision 1 and observed again at revision 2, so the record shows its provenance and a later revision.
    (let [id "browser-ozon:fbs-posting:K1" sale (str "import:" id)
          sold (fn [n status] {:source-operation-id id :entity-id id :connection-id "browser-ozon" :source-id "K1" :revision n
                               :payload-hash (apply str (repeat 64 (str n))) :adapter-version "test" :observed-at at :last-observed-at at
                               :source-revision-id (str id "#" n) :facts (observations/write-json {:status status}) :kind "fbs-posting" :status status
                               :terminal true :occurred-at "2026-11-03T09:00:00Z" :complete true
                               :lines (observations/write-json [{:line-key "1" :identifiers [{:type "sku" :value "106"}] :quantity "1"}])})]
      (put :tover.catalogues.warehouses.model/warehouse-registered {:warehouse-id "browser-shelf" :name "Shelf" :status "active" :revision 1})
      (doseq [[record kind n occurred line] [["browser-shelf-opening" "opening" 0 "2026-11-02T00:00:00Z" {:quantity-change 5 :unit-cost "100.00"}]
                                             [sale "sale" 1 "2026-11-03T09:00:00Z" {:quantity-change -1 :unit-price "250.00"}]]
              :let [rid (str record "#1") acceptance (str "browser-acc-" record)]]
        (put :tover.inventory.acceptance.model/inventory-changes-accepted {:acceptance-id acceptance :actor fixtures/owner-id :recorded-at (java.time.Instant/parse at) :record-count 1})
        (put :tover.inventory.acceptance.model/inventory-record-accepted
             {:record-revision-id rid :record-id record :revision 1 :acceptance-id acceptance :kind kind
              :occurred-at (java.time.Instant/parse occurred) :sequence n :cancelled false})
        (put :tover.inventory.acceptance.model/inventory-record-line-accepted
             (merge {:record-line-id (str rid "/0") :record-revision-id rid :position 0 :stock-item-id "browser-item-6" :warehouse-id "browser-shelf"} line)))
      (put :tover.marketplace.sync.model/source-operation-observed (sold 1 "delivered"))
      (put :tover.marketplace.sync.model/source-operation-observed (sold 2 "customer_received"))
      (put :tover.marketplace.provenance/source-operation-accepted
           {:source-operation-id id :connection-id "browser-ozon" :revision 1 :reflected-revision 1 :record-id sale
            :accepted-by (str (tover.marketplace.sync.model/connector-actor "browser-ozon")) :edited false
            :mappings-used (observations/write-json [{:line-key "1" :external-product-id "browser-ozon:106" :mapping-revision 1}])
            :accepted-at at})))
  ;; This server runs no processors (no journal worker); propose the connection's operations each second as
  ;; Marketplace Operation Intake would. Local only: it reads this store and calls no marketplace.
  (require '[tover.marketplace.operations.model :as imported])
  (future
    (let [bel-system (:bel/system services) requests @#'imported/connection-requests]
      (loop []
        (Thread/sleep 1000)
        (try
          (doseq [request (map imported/marketplace-operation-intake
                               (requests bel-system fixtures/workspace-id "browser-ozon" (java.time.Instant/now)))]
            (try (runtime/execute! (merge (:scope request) {:bel/system bel-system :bel/service ::imported/marketplace-operation-intake
                                                            :user-id (:actor request)})
                                   (:id request)
                                   {:command-id (runtime/processor-command-id ::imported/marketplace-operation-intake request)
                                    :input (:input request)})
                 (catch Exception _ nil)))
          (catch Exception _ nil))
        (recur))))
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
