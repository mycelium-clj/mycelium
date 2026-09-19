(ns mycelium.patch
  "Checked manifest edits — the manifest-editing analog of a compiler patch.
   Ops work on the *raw* manifest (the EDN as written: :fragments, :pipeline
   and :schema :inherit intact), rewrite every reference to a cell, and the
   result is expanded + validated with the full manifest validator before the
   caller ever sees it. An optional expect-hash guard rejects stale edits.

   `render` writes the patched map back over the original text with a minimal
   diff, so comments and layout survive — the EDN file is the human-readable
   projection, and a checked edit must not reformat it."
  (:require [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [borkdude.rewrite-edn :as r]
            [rewrite-clj.node :as n]
            [rewrite-clj.parser :as p]
            [mycelium.fragment :as fragment]
            [mycelium.manifest :as manifest]))

;; ===== canonical hashing =====

(defn- canonical-form
  "Recursively converts a manifest to a key-order-insensitive canonical form:
   maps sorted by key string, sequences kept in order."
  [x]
  (cond
    (map? x)       (into (sorted-map-by (fn [a b] (compare (pr-str a) (pr-str b))))
                         (map (fn [[k v]] [(canonical-form k) (canonical-form v)]))
                         x)
    (sequential? x) (mapv canonical-form x)
    (set? x)        (into (sorted-set-by (fn [a b] (compare (pr-str a) (pr-str b))))
                          (map canonical-form x))
    :else x))

(defn manifest-hash
  "Deterministic sha-256 hex of a manifest map, insensitive to key order and
   whitespace. Hash the raw file form (what `myc patch` edits), not the
   expanded one: fragment files and registered handler schemas are not
   included."
  [m]
  (let [s     (binding [*print-length* nil *print-level* nil *print-meta* false
                        *print-namespace-maps* false]
                (pr-str (canonical-form m)))
        bytes (.digest (java.security.MessageDigest/getInstance "SHA-256")
                       (.getBytes ^String s "UTF-8"))]
    (apply str (map #(format "%02x" %) bytes))))

;; ===== op registry =====

(def ^:private op-registry
  {"rename-cell"
   {:doc  "Rename a cell (or a join / fragment entry alias) and rewrite every reference to it."
    :args [{:name :from :type :cell :doc "existing cell name"}
           {:name :to   :type :cell :doc "new cell name"}]}})

(defn ops
  "Supported patch ops: name → {:doc :args [{:name :type :doc}]}. Arg types:
   :cell (keyword naming a cell; a leading colon is optional on the CLI)."
  []
  op-registry)

;; ===== reference sites =====

(def ^:private terminals #{:end :error :halt})

(defn- fragment-entries
  "fragment :as alias → fragment name, for every mapping that declares :as."
  [{:keys [fragments]}]
  (into {} (keep (fn [[fname fm]] (when (:as fm) [(:as fm) fname]))) fragments))

(defn cell-refs
  "Every place `cell` is referenced in a raw manifest. Returns a vector of
   {:role kw :path [..]} where :path is the get-in path to the reference.
   Roles: :definition :edges-out :edges-in :dispatches :on-error :join
   :join-member :region :constraint :timeout :resilience :error-group
   :error-group-handler :pipeline :fragment-entry :fragment-exit."
  [{:keys [cells edges dispatches joins regions constraints timeouts resilience
           error-groups pipeline fragments]} cell]
  (let [ref (fn [role path] {:role role :path path})]
    (-> []
        (into (when (contains? cells cell) [(ref :definition [:cells cell])]))
        (into (keep (fn [[k def]] (when (and (map? def) (= cell (:on-error def)))
                                    (ref :on-error [:cells k :on-error])))
                    cells))
        (into (when (contains? edges cell) [(ref :edges-out [:edges cell])]))
        (into (mapcat (fn [[k v]]
                        (if (keyword? v)
                          (when (= cell v) [(ref :edges-in [:edges k])])
                          (keep (fn [[label t]] (when (= cell t) (ref :edges-in [:edges k label]))) v)))
                      edges))
        (into (when (contains? dispatches cell) [(ref :dispatches [:dispatches cell])]))
        (into (when (contains? joins cell) [(ref :join [:joins cell])]))
        (into (keep (fn [[k v]] (when (some #{cell} (:cells v)) (ref :join-member [:joins k :cells]))) joins))
        (into (keep (fn [[k v]] (when (some #{cell} v) (ref :region [:regions k]))) regions))
        (into (mapcat (fn [i c]
                        (keep (fn [[k v]]
                                (when (or (= cell v) (and (vector? v) (some #{cell} v)))
                                  (ref :constraint [:constraints i k])))
                              c))
                      (range) constraints))
        (into (when (contains? timeouts cell) [(ref :timeout [:timeouts cell])]))
        (into (when (contains? resilience cell) [(ref :resilience [:resilience cell])]))
        (into (mapcat (fn [[k g]]
                        (cond-> []
                          (some #{cell} (:cells g)) (conj (ref :error-group [:error-groups k :cells]))
                          (= cell (:on-error g))    (conj (ref :error-group-handler [:error-groups k :on-error]))))
                      error-groups))
        (into (keep-indexed (fn [i c] (when (= cell c) (ref :pipeline [:pipeline i]))) pipeline))
        (into (mapcat (fn [[k fm]]
                        (cond-> []
                          (= cell (:as fm)) (conj (ref :fragment-entry [:fragments k :as]))
                          true (into (keep (fn [[label t]] (when (= cell t) (ref :fragment-exit [:fragments k :exits label])))
                                           (:exits fm)))))
                      fragments)))))

;; ===== rename-cell =====

(defn- rename-kw
  [from to x]
  (if (= x from) to x))

(defn- rename-map-keys
  "update-compatible arg order: [m from to] — the threaded map comes first."
  [m from to]
  (into {} (map (fn [[k v]] [(rename-kw from to k) v])) (or m {})))

(defn- rename-edge-def
  [from to edge-def]
  (if (keyword? edge-def)
    (rename-kw from to edge-def)
    (into {} (map (fn [[label target]] [label (rename-kw from to target)])) edge-def)))

(defn- owning-fragment
  "Finds the fragment mapping whose (resolved) fragment defines `cell`.
   Returns [fragment-name mapping] or nil. Resolves :ref paths relative to
   fragment/*fragment-dir*, like expansion does."
  [{:keys [fragments]} cell]
  (some (fn [[fname fm]]
          (let [frag (or (:fragment fm)
                         (when-let [ref (:ref fm)]
                           (try (fragment/load-fragment ref) (catch Exception _ nil))))]
            (when (contains? (:cells frag) cell) [fname fm])))
        fragments))

(defn- rename-cell*
  "Rewrites every reference to `from` across all raw manifest sections.
   `expanded` is the fragment-expanded manifest, used to refuse renames of
   cells that live inside a fragment and to detect collisions with them."
  [{:keys [cells edges dispatches joins regions constraints timeouts
           resilience error-groups pipeline fragments] :as m}
   expanded from to]
  (let [joins       (or joins {})
        entries     (fragment-entries m)
        raw-name?   (fn [k] (or (contains? cells k) (contains? joins k) (contains? entries k)))]
    (cond
      (contains? terminals from)
      (throw (ex-info (str "Cannot rename " from " — it is a terminal state") {:from from}))

      (= from :start)
      (throw (ex-info "Cannot rename :start — reachability is BFS-rooted at :start"
                      {:from from}))

      (not (raw-name? from))
      (if-let [[fname fm] (when (contains? (:cells expanded) from) (owning-fragment m from))]
        (throw (ex-info (str "Cell " from " is defined by fragment " fname
                             " (" (or (:ref fm) "inline") ") — rename it in the fragment file")
                        {:cell from :fragment fname :ref (:ref fm)}))
        (throw (ex-info (str "Unknown cell " from " — not found in :cells, :joins or fragment aliases")
                        {:cell from :available (set (concat (keys cells) (keys joins) (keys entries)))})))

      (or (raw-name? to) (contains? (:cells expanded) to) (contains? terminals to))
      (throw (ex-info (str "Cannot rename " from " to " to " — " to " already exists")
                      {:from from :to to}))

      :else
      (let [update-vec (fn [v] (mapv #(rename-kw from to %) v))]
        (cond-> m
          cells        (update :cells
                               (fn [cs] (into {}
                                              (map (fn [[k def]]
                                                     [(rename-kw from to k)
                                                      (if (and (map? def) (contains? def :on-error))
                                                        (update def :on-error #(rename-kw from to %))
                                                        def)]))
                                              cs)))
          edges        (update :edges
                               (fn [es] (into {}
                                              (map (fn [[k v]]
                                                     [(rename-kw from to k) (rename-edge-def from to v)]))
                                              es)))
          dispatches   (update :dispatches rename-map-keys from to)
          (seq joins)  (update :joins
                               (fn [js] (rename-map-keys
                                         (into {}
                                               (map (fn [[k v]]
                                                      [k (if (map? v) (update v :cells update-vec) v)]))
                                               js)
                                         from to)))
          regions      (update :regions
                               (fn [rs] (into {}
                                              (map (fn [[k v]] [k (update-vec v)]))
                                              rs)))
          constraints  (update :constraints
                               (fn [cs] (mapv (fn [c]
                                                (into {}
                                                      (map (fn [[k v]]
                                                             [k (cond
                                                                  (= v from)   to
                                                                  (vector? v)  (update-vec v)
                                                                  :else v)]))
                                                      c))
                                              cs)))
          timeouts     (update :timeouts rename-map-keys from to)
          resilience   (update :resilience rename-map-keys from to)
          error-groups (update :error-groups
                               (fn [gs] (into {}
                                              (map (fn [[k {:keys [cells on-error] :as g}]]
                                                     [k (cond-> g
                                                          cells (assoc :cells (update-vec cells))
                                                          on-error (assoc :on-error (rename-kw from to on-error)))]))
                                              gs)))
          pipeline     (update :pipeline update-vec)
          fragments    (update :fragments
                               (fn [fs] (into {}
                                              (map (fn [[k fm]]
                                                     [k (cond-> fm
                                                          (:as fm)    (update :as #(rename-kw from to %))
                                                          (:exits fm) (update :exits
                                                                              (fn [ex] (into {} (map (fn [[l t]] [l (rename-kw from to t)])) ex))))]))
                                              fs))))))))

;; ===== op application =====

(defn- default-validate
  [manifest-opts]
  (fn [m] (manifest/validate-manifest (manifest/expand-fragments m manifest-opts) manifest-opts)))

(defn apply-op
  "Applies a single op map to a raw manifest. Supported ops: see `ops`, e.g.
     {:op \"rename-cell\" :from :old :to :new}
   The input is validated before the edit and the result is re-validated
   (fragments expanded) before returning; the returned map is still the raw
   form. opts:
     :fragment-dir  — directory for resolving fragment :ref paths
     :manifest-opts — passed to validation (default {:strict? false})"
  ([m op] (apply-op m op {}))
  ([m {:keys [op from to] :as _op} {:keys [fragment-dir manifest-opts]
                                    :or   {manifest-opts {:strict? false}}}]
   (binding [fragment/*fragment-dir* (or fragment-dir fragment/*fragment-dir*)]
     (let [validate (default-validate manifest-opts)]
       (case op
         "rename-cell" (let [expanded (validate m)
                             result   (rename-cell* m expanded from to)]
                         (validate result)
                         result)
         (throw (ex-info (str "Unknown op: " op ". Supported: "
                              (str/join ", " (sort (keys op-registry))))
                         {:op op})))))))

(defn apply-ops
  "Applies ops sequentially with an optional expect-hash guard.
   Opts: {:expect-hash \"...\" :ops [{...} ...]} plus apply-op's opts."
  [m {:keys [expect-hash ops] :as opts}]
  (when expect-hash
    (let [current (manifest-hash m)]
      (when (not= expect-hash current)
        (throw (ex-info (str "Stale manifest: expected hash " expect-hash
                             " but current hash: " current
                             " — re-read the manifest and re-apply")
                        {:expected expect-hash :current current})))))
  (reduce (fn [acc op] (apply-op acc op opts)) m ops))

;; ===== rendering back to text =====

(defn pprint-str
  "Pretty-prints a manifest value with the printer settings a file needs:
   no truncation, no namespaced-map syntax, no metadata."
  [v]
  (binding [*print-length* nil *print-level* nil *print-meta* false
            *print-namespace-maps* false]
    (str/trimr (with-out-str (pprint/pprint v)))))

(defn- value-node
  [v]
  (if (coll? v)
    (p/parse-string (pprint-str v))
    (n/coerce v)))

(defn- map-node? [node] (= :map (n/tag node)))

(defn- sync-node
  "Returns `node` (whose value is `old`) edited to have the value `new`,
   changing as little of the node tree as possible: renamed keys stay in
   place, untouched entries keep their text, and only changed leaves are
   reprinted."
  [node old new]
  (cond
    (= old new) node

    (and (map? old) (map? new) (map-node? node))
    (let [old-ks  (keys old)
          new-ks  (keys new)
          removed (remove (set new-ks) old-ks)
          added   (remove (set old-ks) new-ks)
          ;; a removed key whose value reappears under an added key is a rename
          renames (loop [rs removed, as added, acc {}]
                    (if-let [rk (first rs)]
                      (if-let [ak (some #(when (= (get old rk) (get new %)) %) as)]
                        (recur (rest rs) (remove #{ak} as) (assoc acc rk ak))
                        (recur (rest rs) as acc))
                      acc))
          node    (if (seq renames)
                    (r/map-keys (fn [k] (let [v (r/sexpr k)]
                                          (if (contains? renames v) (n/coerce (renames v)) k)))
                                node)
                    node)
          node    (reduce (fn [nd k] (r/dissoc nd k)) node (remove renames removed))
          node    (reduce (fn [nd k] (r/assoc nd k (value-node (get new k))))
                          node (remove (set (vals renames)) added))]
      (reduce (fn [nd k]
                (let [ov (get old k) nv (get new k)]
                  (if (= ov nv) nd (r/assoc nd k (sync-node (r/get nd k) ov nv)))))
              node
              (filter (set new-ks) old-ks)))

    :else (value-node new)))

(defn render
  "Returns `text` (the file the raw map `old` was read from) rewritten so it
   reads as `new`, preserving comments and layout wherever the value did not
   change. Falls back to a full pretty-print when `text` does not parse to
   `old` or the minimal rewrite does not round-trip."
  [text old new]
  (let [out (try
              (let [forms    (r/parse-string text)
                    children (vec (n/children forms))
                    idx      (first (keep-indexed (fn [i c] (when (map-node? c) i)) children))]
                (when (and idx (= old (n/sexpr (nth children idx))))
                  (n/string (n/replace-children
                             forms
                             (assoc children idx (sync-node (nth children idx) old new))))))
              (catch Exception _ nil))]
    (if (and out (= new (edn/read-string out)))
      out
      (str (pprint-str new) "\n"))))
