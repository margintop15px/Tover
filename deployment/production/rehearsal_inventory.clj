;; Offline inventory check for rehearse.py, evaluated with clojure.main inside the stopped app's container.
;; `seed` registers a warehouse, supplier and product, accepts one purchase of 17 and leaves an incomplete
;; shared draft and prints the review fingerprint; `verify` (after seeding and after restore) reads them back and
;; retries the identical acceptance request, which must return the durable receipt.
;; It runs the production Bel commands on the production stores as the user rehearse.py signed in, under the real
;; session authority and row policies.
(ns rehearsal-inventory
  (:require
    [bel.core :as bel]
    [bel.delivery :as delivery]
    [bel.runtime :as runtime]
    [tover.catalogues.products.model :as products]
    [tover.catalogues.suppliers.model :as suppliers]
    [tover.catalogues.warehouses.model :as warehouses]
    [tover.identity.access :as access]
    [tover.inventory.acceptance.model :as acceptance]
    [tover.inventory.entries.model :as entries]
    [tover.inventory.stock.model :as stock]))


(def item "item:rehearsal-line")


(defn- execute! [ctx command id input]
  (runtime/execute! ctx command {:command-id id :input input}))


(def selection {:selection [{:entry-id "rehearsal-purchase" :revision 1}] :order ["rehearsal-purchase"]})


(defn- accept! [ctx workspace fingerprint]
  (execute! ctx ::acceptance/accept-reviewed-inventory-changes "rehearsal-accept"
            (merge selection {:workspace-id workspace :acceptance-id "rehearsal-accept" :fingerprint fingerprint})))


(defn- seed!
  "Register catalogue records, accept one purchase of 17, leave an incomplete draft; return the review fingerprint."
  [ctx workspace]
  (execute! ctx ::warehouses/register-warehouse "rehearsal-w" {:warehouse-id "rehearsal-main" :name "Rehearsal warehouse"})
  (execute! ctx ::suppliers/register-supplier "rehearsal-s" {:supplier-id "rehearsal-supplier" :name "Rehearsal supplier"})
  (execute! ctx ::products/register-product "rehearsal-p" {:product-id "rehearsal-item" :name "Rehearsal item"})
  (execute! ctx ::entries/save-shared-inventory-entry "rehearsal-e1"
            {:workspace-id workspace :entry-id "rehearsal-purchase" :kind "purchase" :supplier-id "rehearsal-supplier"
             :occurred-at  "2026-10-20T00:00:00Z"
             :lines        [{:line-id  "rehearsal-line" :product-id "rehearsal-item" :unit "pcs" :warehouse-id "rehearsal-main"
                             :quantity "17"             :unit-cost  "100.00"}]})
  (let [review (runtime/read-model ctx ::acceptance/inventory-review selection)]
    (assert (:valid? review) (pr-str (:problems review)))
    (accept! ctx workspace (:fingerprint review))
    (execute! ctx ::entries/save-shared-inventory-entry "rehearsal-e2"
              {:workspace-id workspace :entry-id "rehearsal-pending" :kind "purchase"})
    (:fingerprint review)))


(defn- verify!
  "Read back the accepted stock, history and draft, then retry the identical acceptance request."
  [ctx workspace fingerprint]
  (let [result   (runtime/read-model ctx ::acceptance/acceptance-result {:acceptance-id "rehearsal-accept"})
        pending  (:entries (runtime/read-model ctx ::entries/pending-entries {}))
        history  (runtime/read-model ctx ::stock/item-history {:stock-item-id item})
        accepted (runtime/read-model ctx ::acceptance/accepted-operations {})]
    (assert (= #{[item "rehearsal-main" 17]} (set (map (juxt :stock-item-id :warehouse-id :quantity) (:balances result))))
            (pr-str (:balances result)))
    (assert (= ["rehearsal-pending"] (mapv :entry-id pending)) (pr-str (map :entry-id pending)))
    (assert (= ["purchase"] (mapv :kind accepted)) (pr-str accepted))
    (assert (seq (:rows history)) (pr-str history))
    ;; Retrying the committed command returns its durable receipt instead of accepting twice.
    (accept! ctx workspace fingerprint)
    (assert (= 1 (count (runtime/read-model ctx ::acceptance/accepted-operations {}))))
    "PASS: accepted purchase of 17, item history, incomplete shared draft and receipt retry"))


(defn rehearse!
  "`seed` returns the review fingerprint; `verify` needs it and returns a pass line. `services` holds the provider
  and session store; `session-key` is the signed-in rehearsal user's session with the workspace selected."
  [mode bel-system repo services session-key workspace fingerprint]
  (bel/init! {:scan-ns ['tover] :scan-exclude-ns ['tover.catalogues.rename]})
  (let [identity (access/current-identity services session-key)
        _        (assert (= workspace (:workspace-id identity)) "The rehearsal session has no selected workspace")
        ctx      (runtime/prepare-context (merge {:bel/system bel-system :repo repo} identity))]
    (case mode
      "seed" (seed! ctx workspace)
      "verify" (do
                 ;; The running app's journal worker has moved every accepted command out of the RDF outbox.
                 (assert (empty? (delivery/pending bel-system)) "The journal worker left accepted commands undelivered")
                 (verify! ctx workspace fingerprint)))))
