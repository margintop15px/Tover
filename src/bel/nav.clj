(ns bel.nav)


(defn link
  "Build a real link that uses Datastar for ordinary internal navigation."
  {:malli/schema
   [:function
    [:=> [:cat :string] :map]
    [:=> [:cat :string :map] :map]]}
  ([url]
   (link url {}))

  ([url attrs]
   (assoc attrs
          :href url
          :data-on:click "if (!el.hash) { @navigate(el.href) }")))
