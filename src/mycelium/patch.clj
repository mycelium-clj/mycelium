(ns mycelium.patch
  "Checked manifest edits — the manifest-editing analog of a compiler patch.
   Ops work on the *raw* manifest (the EDN as written: :fragments, :pipeline
   and :schema :inherit intact), rewrite every reference to a cell, and the
   result is expanded + validated with the full manifest validator before the
   caller ever sees it. An optional expect-hash guard rejects stale edits.

   `render` writes the patched map back over the original text with a minimal
   diff, so comments and layout survive — the EDN file is the human-readable
   projection, and a checked edit must not reformat it. Map edits are done by
   key *position* on the rewrite-clj node tree: in an edge map like
   {:start :delete, :delete :end} the key :delete and the value :delete must
   never be confused."
  (:require [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [clojure.set :as set]
            [clojure.string :as str]
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

(defn cell-kw
  "Cell name from a CLI token: `start`, `:start`, `auth/validate` all work."
  [s]
  (cond
    (keyword? s) s
    (nil? s)     nil
    :else        (keyword (if (str/starts-with? s ":") (subs s 1) s))))

(defn coerce-field-value
  "Parses a `set-cell-field` value the way its field expects: :doc is text,
   :id / :on-error are keywords (\"nil\" → nil), anything else is EDN."
  [field s]
  (if-not (string? s)
    s
    (case field
      :doc      s
      :id       (cell-kw s)
      :on-error (when (not= "nil" s) (cell-kw s))
      (edn/read-string s))))

(def ^:private op-registry
  {"rename-cell"
   {:doc  "Rename a cell (or a join / fragment entry alias) and rewrite every reference to it."
    :args [{:name :from :type :cell :required? true :doc "existing cell name"}
           {:name :to   :type :cell :required? true :doc "new cell name"}]}

   "add-cell"
   {:doc  "Add a cell definition. Wire it with --edges, --after, or a set-edge op in the same patch."
    :args [{:name :name       :type :cell        :required? true :doc "new cell name"}
           {:name :id         :type :keyword     :required? true :doc "handler id, e.g. app/validate"}
           {:name :doc        :type :string      :required? true :doc "what the cell does"}
           {:name :input      :type :edn         :doc "input schema (default [:map])"}
           {:name :output     :type :edn         :doc "output schema (default [:map])"}
           {:name :on-error   :type :cell-or-nil :doc "error target cell (default nil)"}
           {:name :requires   :type :edn         :doc "resource keys, e.g. [:db]"}
           {:name :edges      :type :edn         :doc "outgoing edge: target keyword or {label target}"}
           {:name :dispatches :type :edn         :doc "[[label (fn [d] ...)] ...] when --edges is a map"}
           {:name :after      :type :cell        :doc "splice after this cell (its unconditional edge, or :pipeline order)"}]}

   "remove-cell"
   {:doc  "Remove a cell and its own entries (edges, dispatches, timeouts, memberships)."
    :args [{:name :name   :type :cell :required? true :doc "cell to remove"}
           {:name :rewire :type :cell :doc "retarget incoming edges / :on-error / exits to this cell"}]}

   "set-edge"
   {:doc  "Set a cell's outgoing edge, or one labelled transition of it."
    :args [{:name :from  :type :cell    :required? true :doc "source cell"}
           {:name :to    :type :cell    :required? true :doc "target cell or terminal (end, error, halt)"}
           {:name :label :type :keyword :doc "transition label; omit for an unconditional edge"}]}

   "delete-edge"
   {:doc  "Delete a cell's outgoing edge, or one labelled transition (and its dispatch predicate)."
    :args [{:name :from  :type :cell    :required? true :doc "source cell"}
           {:name :label :type :keyword :doc "transition label; omit to delete the whole edge"}]}

   "set-cell-field"
   {:doc  "Set one field of a cell definition. --expect fails if the current value differs."
    :args [{:name :name   :type :cell        :required? true :doc "cell name"}
           {:name :field  :type :keyword     :required? true :doc "doc, id, schema, on-error, requires, params, ..."}
           {:name :value  :type :field-value :required? true :doc "new value (text for doc, keyword for id/on-error, EDN otherwise)"}
           {:name :expect :type :field-value :doc "current value the edit assumes"}]
    :coerce (fn [{:keys [field] :as op}]
              (cond-> op
                (contains? op :value)  (update :value  #(coerce-field-value field %))
                (contains? op :expect) (update :expect #(coerce-field-value field %))))}

   "set-dispatches"
   {:doc  "Replace a cell's dispatch predicates."
    :args [{:name :name       :type :cell :required? true :doc "cell name"}
           {:name :dispatches :type :edn  :required? true :doc "[[label (fn [d] ...)] ...]"}]}})

(defn ops
  "Supported patch ops: name → {:doc :args [{:name :type :required? :doc}]}.
   Arg types: :cell / :keyword (leading colon optional), :cell-or-nil (\"nil\"
   allowed), :string, :edn, :field-value (parsed per --field)."
  []
  op-registry)

(defn- coerce-arg
  [type v]
  (if-not (string? v)
    v
    (case type
      (:cell :keyword) (cell-kw v)
      :cell-or-nil     (when (not= "nil" v) (cell-kw v))
      :edn             (edn/read-string v)
      v)))

(defn coerce-op
  "Turns a CLI op group {:op \"name\" :arg \"string\" ...} into the map
   apply-op expects, using the registry's arg types. Throws with :exit 64
   when a required arg is missing. Unknown ops pass through so apply-op
   reports them; non-string values pass through untouched."
  [{:keys [op] :as group}]
  (if-let [spec (get op-registry op)]
    (let [m (reduce (fn [m {arg :name type :type required? :required?}]
                      (cond
                        (contains? group arg) (assoc m arg (coerce-arg type (get group arg)))
                        required? (throw (ex-info (str "--op " op " requires --" (name arg)
                                                       " (args: "
                                                       (str/join " " (map #(str "--" (name (:name %))
                                                                                (when-not (:required? %) "?"))
                                                                          (:args spec)))
                                                       ")")
                                                  {:exit 64 :op op :missing arg}))
                        :else m))
                    {:op op}
                    (:args spec))]
      (if-let [f (:coerce spec)] (f m) m))
    group))

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

(defn- raw-name?
  "Is `k` a name the raw manifest itself defines: a cell, a join, or a
   fragment's :as alias?"
  [{:keys [cells joins] :as m} k]
  (or (contains? cells k) (contains? (or joins {}) k) (contains? (fragment-entries m) k)))

(defn- fragment-cell-error
  "ex-info for an operation aimed at a cell that lives inside a fragment."
  [m cell verb]
  (let [[fname fm] (owning-fragment m cell)]
    (ex-info (str "Cell " cell " is defined by fragment " fname
                  " (" (or (:ref fm) "inline") ") — " verb " it in the fragment file")
             {:cell cell :fragment fname :ref (:ref fm)})))

(defn- unknown-cell-error
  [{:keys [cells joins] :as m} cell]
  (ex-info (str "Unknown cell " cell " — not found in :cells, :joins or fragment aliases")
           {:cell cell :available (set (concat (keys cells) (keys joins) (keys (fragment-entries m))))}))

(defn- name-taken-error
  [what name]
  (ex-info (str what " — " name " already exists") {:name name}))

(defn- name-taken?
  "Is `k` already a cell (raw or fragment-contributed), join, alias or terminal?"
  [m {:keys [fragment-cells]} k]
  (or (raw-name? m k) (contains? fragment-cells k) (contains? terminals k)))

(defn- rename-cell*
  "Rewrites every reference to `from` across all raw manifest sections.
   ctx :fragment-cells is the set of cell names contributed by fragments,
   used to refuse renaming a cell that lives inside one and to detect
   collisions."
  [{:keys [cells edges dispatches joins regions constraints timeouts
           resilience error-groups pipeline fragments] :as m}
   {:keys [fragment-cells] :as ctx} from to]
  (let [joins (or joins {})]
    (cond
      (contains? terminals from)
      (throw (ex-info (str "Cannot rename " from " — it is a terminal state") {:from from}))

      (= from :start)
      (throw (ex-info "Cannot rename :start — reachability is BFS-rooted at :start"
                      {:from from}))

      (not (raw-name? m from))
      (throw (if (contains? fragment-cells from)
               (fragment-cell-error m from "rename")
               (unknown-cell-error m from)))

      (name-taken? m ctx to)
      (throw (name-taken-error (str "Cannot rename " from " to " to) to))

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

;; ===== add-cell / remove-cell / set-edge / delete-edge / set-cell-field =====

(defn- require-raw-cell!
  [m ctx cell]
  (when-not (contains? (:cells m) cell)
    (throw (if (contains? (:fragment-cells ctx) cell)
             (fragment-cell-error m cell "edit")
             (unknown-cell-error m cell)))))

(defn- require-edge-source!
  "Edges are declared for raw cells and joins; fragment cells carry their
   own edges in the fragment file."
  [m ctx cell]
  (when-not (or (contains? (:cells m) cell) (contains? (or (:joins m) {}) cell))
    (throw (if (contains? (:fragment-cells ctx) cell)
             (fragment-cell-error m cell "edit the edges of")
             (unknown-cell-error m cell)))))

(defn- require-edges-manifest!
  [m op]
  (when (:pipeline m)
    (throw (ex-info (str op ": this manifest uses :pipeline — edit the :pipeline order "
                         "(add-cell --after / remove-cell), or convert it to :edges first")
                    {:op op}))))

(defn- add-cell*
  [m ctx {:keys [name id doc input output on-error requires edges dispatches after]}]
  (when (name-taken? m ctx name)
    (throw (name-taken-error (str "Cannot add " name) name)))
  (when after (require-raw-cell! m ctx after))
  (let [def (cond-> {:id id :doc doc
                     :schema {:input (or input [:map]) :output (or output [:map])}
                     :on-error on-error}
              requires (assoc :requires requires))
        m   (assoc-in m [:cells name] def)]
    (if (:pipeline m)
      (do (when (or edges dispatches)
            (throw (ex-info "add-cell: --edges/--dispatches do not apply to a :pipeline manifest; use --after"
                            {:op "add-cell"})))
          (update m :pipeline
                  (fn [p] (if after
                            (let [i (.indexOf ^java.util.List p after)]
                              (vec (concat (take (inc i) p) [name] (drop (inc i) p))))
                            (conj p name)))))
      (let [m (cond-> m
                edges      (assoc-in [:edges name] edges)
                dispatches (assoc-in [:dispatches name] dispatches))]
        (if after
          (let [prev (get-in m [:edges after])]
            (when-not (keyword? prev)
              (throw (ex-info (str "add-cell --after " after ": its edge is conditional (" (pr-str prev)
                                   ") — wire the new cell with set-edge --from " after " --label <l> --to " name)
                              {:op "add-cell" :after after :edge prev})))
            (cond-> (assoc-in m [:edges after] name)
              (not edges) (assoc-in [:edges name] prev)))
          m)))))

(defn- retarget
  [x from to]
  (if (= x from) to x))

(defn- remove-cell*
  [m ctx {:keys [name rewire]}]
  (when (= name :start)
    (throw (ex-info "Cannot remove :start — reachability is BFS-rooted at :start" {:cell name})))
  (require-raw-cell! m ctx name)
  (when rewire
    (when-not (or (raw-name? m rewire) (contains? (:fragment-cells ctx) rewire) (contains? terminals rewire))
      (throw (unknown-cell-error m rewire))))
  (let [auto     #{:definition :edges-out :dispatches :timeout :resilience :region
                   :error-group :join-member :pipeline}
        rewirable #{:edges-in :on-error :error-group-handler :fragment-exit}
        refs     (remove #(auto (:role %)) (cell-refs m name))
        blocking (if rewire (remove #(rewirable (:role %)) refs) refs)]
    (when (seq blocking)
      (throw (ex-info (str "Cell " name " is still referenced: "
                           (str/join ", " (map (fn [{:keys [role path]}]
                                                 (str (clojure.core/name role) " " (str/join " " (map pr-str path))))
                                               blocking))
                           (if rewire
                             " — edit these by hand first"
                             " — pass --rewire <cell> to retarget them, or remove them first"))
                      {:cell name :refs blocking})))
    (let [drop-member (fn [v] (vec (remove #{name} v)))
          update-if   (fn [m k f] (if (contains? m k) (update m k f) m))
          retarget-in (fn [m]
                        (-> m
                            (update :cells (fn [cs] (into {} (map (fn [[k d]]
                                                                   [k (if (and (map? d) (contains? d :on-error))
                                                                        (update d :on-error retarget name rewire)
                                                                        d)]))
                                                           cs)))
                            (update :edges (fn [es] (into {} (map (fn [[k v]]
                                                                   [k (if (keyword? v)
                                                                        (retarget v name rewire)
                                                                        (into {} (map (fn [[l t]] [l (retarget t name rewire)])) v))]))
                                                           es)))
                            (update-if :error-groups
                                       (fn [gs] (into {} (map (fn [[k g]] [k (update g :on-error retarget name rewire)])) gs)))
                            (update-if :fragments
                                       (fn [fs] (into {} (map (fn [[k fm]]
                                                               [k (cond-> fm
                                                                    (:exits fm) (update :exits (fn [ex] (into {} (map (fn [[l t]] [l (retarget t name rewire)])) ex))))]))
                                                      fs)))))]
      (cond-> (-> m
                  (update :cells dissoc name)
                  (update-if :edges #(dissoc % name))
                  (update-if :dispatches #(dissoc % name))
                  (update-if :timeouts #(dissoc % name))
                  (update-if :resilience #(dissoc % name))
                  (update-if :regions (fn [rs] (into {} (map (fn [[k v]] [k (drop-member v)])) rs)))
                  (update-if :error-groups (fn [gs] (into {} (map (fn [[k g]] [k (update g :cells drop-member)])) gs)))
                  (update-if :joins (fn [js] (into {} (map (fn [[k j]] [k (if (map? j) (update j :cells drop-member) j)])) js)))
                  (update-if :pipeline drop-member))
        rewire retarget-in))))

(defn- set-edge*
  [m ctx {:keys [from to label]}]
  (require-edges-manifest! m "set-edge")
  (require-edge-source! m ctx from)
  (if label
    (update-in m [:edges from] (fn [prev] (assoc (if (map? prev) prev {}) label to)))
    (assoc-in m [:edges from] to)))

(defn- delete-edge*
  [m ctx {:keys [from label]}]
  (require-edges-manifest! m "delete-edge")
  (require-edge-source! m ctx from)
  (when-not (contains? (:edges m) from)
    (throw (ex-info (str "delete-edge: " from " has no edge") {:cell from})))
  (if label
    (let [prev (get-in m [:edges from])]
      (when-not (and (map? prev) (contains? prev label))
        (throw (ex-info (str "delete-edge: " from " has no transition " label " (edge: " (pr-str prev) ")")
                        {:cell from :label label :edge prev})))
      (let [edge (dissoc prev label)
            m    (if (empty? edge)
                   (update m :edges dissoc from)
                   (assoc-in m [:edges from] edge))
            ds   (some->> (get-in m [:dispatches from]) (remove #(= label (first %))) vec)]
        (cond
          (nil? ds)   m
          (empty? ds) (update m :dispatches dissoc from)
          :else       (assoc-in m [:dispatches from] ds))))
    (-> m (update :edges dissoc from) (update :dispatches dissoc from))))

(defn- set-cell-field*
  [m ctx {:keys [name field value] :as op}]
  (require-raw-cell! m ctx name)
  (when (contains? op :expect)
    (let [current (get-in m [:cells name field])]
      (when (not= (:expect op) current)
        (throw (ex-info (str "set-cell-field " name " " field ": expected " (pr-str (:expect op))
                             " but found " (pr-str current) " — re-read the cell and retry")
                        {:cell name :field field :expected (:expect op) :current current})))))
  (assoc-in m [:cells name field] value))

(defn- set-dispatches*
  [m ctx {:keys [name dispatches]}]
  (require-edge-source! m ctx name)
  (assoc-in m [:dispatches name] dispatches))

;; ===== op application =====

(defn- apply-op*
  "Applies one op with no validation; ctx carries :fragment-cells."
  [m ctx {:keys [op] :as op-map}]
  (case op
    "rename-cell"    (rename-cell* m ctx (:from op-map) (:to op-map))
    "add-cell"       (add-cell* m ctx op-map)
    "remove-cell"    (remove-cell* m ctx op-map)
    "set-edge"       (set-edge* m ctx op-map)
    "delete-edge"    (delete-edge* m ctx op-map)
    "set-cell-field" (set-cell-field* m ctx op-map)
    "set-dispatches" (set-dispatches* m ctx op-map)
    (throw (ex-info (str "Unknown op: " op ". Supported: "
                         (str/join ", " (sort (keys op-registry))))
                    {:op op}))))

(defn- validator
  [manifest-opts]
  (fn [m] (manifest/validate-manifest (manifest/expand-fragments m manifest-opts) manifest-opts)))

(defn apply-ops
  "Applies ops in order to a raw manifest, with an optional expect-hash
   guard. The input is validated once before any edit and the result once
   after all of them (fragments expanded), so a batch may pass through
   states that are not individually valid — add a cell and wire it in the
   same patch. Returns the raw form. Opts:
     :ops           — [{:op \"name\" ...} ...], see `ops`
     :expect-hash   — sha from `manifest-hash`; mismatch throws
     :fragment-dir  — directory for resolving fragment :ref paths
     :manifest-opts — passed to validation (default {:strict? false})"
  [m {:keys [expect-hash ops fragment-dir manifest-opts]
      :or   {manifest-opts {:strict? false}}}]
  (when expect-hash
    (let [current (manifest-hash m)]
      (when (not= expect-hash current)
        (throw (ex-info (str "Stale manifest: expected hash " expect-hash
                             " but current hash: " current
                             " — re-read the manifest and re-apply")
                        {:expected expect-hash :current current})))))
  (binding [fragment/*fragment-dir* (or fragment-dir fragment/*fragment-dir*)]
    (let [validate (validator manifest-opts)
          expanded (validate m)
          ctx      {:fragment-cells (set/difference (set (keys (:cells expanded)))
                                                    (set (keys (:cells m))))}
          result   (reduce (fn [acc op] (apply-op* acc ctx op)) m ops)]
      (validate result)
      result)))

(defn apply-op
  "Applies a single op map to a raw manifest (validated before and after).
   Supported ops: see `ops`, e.g. {:op \"rename-cell\" :from :old :to :new}.
   opts as for apply-ops."
  ([m op] (apply-op m op {}))
  ([m op opts] (apply-ops m (assoc opts :ops [op]))))

;; ===== diff =====

(defn- keyed-diff
  "Diff of two maps keyed by cell name: {:added [..] :removed [..] :changed {k [old new]}}."
  [a b changed-fn]
  (let [a (or a {}) b (or b {})]
    {:added   (vec (sort-by str (remove #(contains? a %) (keys b))))
     :removed (vec (sort-by str (remove #(contains? b %) (keys a))))
     :changed (into (sorted-map-by #(compare (str %1) (str %2)))
                    (keep (fn [k] (when (and (contains? b k) (not= (get a k) (get b k)))
                                    [k (changed-fn (get a k) (get b k))])))
                    (keys a))}))

(defn diff-manifests
  "Semantic diff of two raw manifests. Returns
     {:same? bool
      :id [old new]               ; only when different
      :cells {:added :removed :changed {name {field [old new]}}}
      :edges {:added :removed :changed {from [old new]}}
      :dispatches {...same shape...}
      :sections {key [old new]}}  ; every other top-level key that differs"
  [a b]
  (let [cells      (keyed-diff (:cells a) (:cells b)
                               (fn [x y] (into (sorted-map)
                                               (keep (fn [f] (when (not= (get x f) (get y f)) [f [(get x f) (get y f)]])))
                                               (distinct (concat (keys x) (keys y))))))
        edges      (keyed-diff (:edges a) (:edges b) vector)
        dispatches (keyed-diff (:dispatches a) (:dispatches b) vector)
        sections   (into (sorted-map)
                         (keep (fn [k] (when (not= (get a k) (get b k)) [k [(get a k) (get b k)]])))
                         (remove #{:id :cells :edges :dispatches} (distinct (concat (keys a) (keys b)))))
        empty-diff? (fn [{:keys [added removed changed]}] (and (empty? added) (empty? removed) (empty? changed)))]
    (cond-> {:same? (and (= (:id a) (:id b)) (empty-diff? cells) (empty-diff? edges)
                         (empty-diff? dispatches) (empty? sections))
             :cells cells :edges edges :dispatches dispatches :sections sections}
      (not= (:id a) (:id b)) (assoc :id [(:id a) (:id b)]))))

;; ===== rendering back to text =====

(defn pprint-str
  "Pretty-prints a manifest value with the printer settings a file needs:
   no truncation, no namespaced-map syntax, no metadata."
  [v]
  (binding [*print-length* nil *print-level* nil *print-meta* false
            *print-namespace-maps* false]
    (str/trimr (with-out-str (pprint/pprint v)))))

(defn- value-node
  "Node for a new value. Collections are pretty-printed; when the column the
   value will start at is known, continuation lines are indented to it."
  ([v] (value-node v nil))
  ([v col]
   (if (coll? v)
     (let [text (pprint-str v)
           text (if (and col (str/includes? text "\n"))
                  (str/replace text "\n" (str "\n" (apply str (repeat (dec col) " "))))
                  text)]
       (p/parse-string text))
     (n/coerce v))))

(defn- map-node? [node] (= :map (n/tag node)))

(defn- layout-node?
  "Whitespace, newline, comma or comment — nodes that carry no value."
  [node]
  (contains? #{:whitespace :newline :comma :comment} (n/tag node)))

(defn- map-entries
  "Key/value child positions of a map node, in order: [{:key v :k i :v j} ...].
   Keys and values are the value-carrying children, alternating; layout nodes
   and #_ discards are skipped. Works by position, so a key that also occurs
   as a value elsewhere in the map is never confused with it."
  [map-node]
  (let [cs  (vec (n/children map-node))
        idx (keep-indexed (fn [i c] (when-not (n/printable-only? c) i)) cs)]
    (mapv (fn [[k v]] {:key (n/sexpr (cs k)) :k k :v v})
          (partition 2 idx))))

(defn- find-entry [map-node k]
  (some #(when (= k (:key %)) %) (map-entries map-node)))

(defn- get-node [map-node k]
  (when-let [{:keys [v]} (find-entry map-node k)]
    (nth (n/children map-node) v)))

(defn- set-value-node [map-node k v-node]
  (let [{:keys [v]} (find-entry map-node k)]
    (n/replace-children map-node (assoc (vec (n/children map-node)) v v-node))))

(defn- set-key-node [map-node k new-k]
  (let [{:keys [k]} (find-entry map-node k)]
    (n/replace-children map-node (assoc (vec (n/children map-node)) k (n/coerce new-k)))))

(defn- append-entry
  "Appends `k` → `v` to a map node. A map that spans lines gets the new entry
   on its own line at the column of its last key (parsed nodes carry their
   position as metadata); a one-line map gets it after a space. A multi-line
   value is indented to the column it starts at."
  [map-node k v]
  (let [cs        (vec (n/children map-node))
        entries   (map-entries map-node)
        last-e    (peek entries)
        key-col   (when last-e (:col (meta (cs (:k last-e)))))
        ;; layout between the previous entry and the last one, when there is one
        prev-sep  (when (>= (count entries) 2)
                    (filterv #(contains? #{:whitespace :newline :comma} (n/tag %))
                             (subvec cs (inc (:v (nth entries (- (count entries) 2)))) (:k last-e))))
        ;; own line when the map already spans lines, or the value will
        multi?    (or (some #(= :newline (n/tag %)) prev-sep)
                      (let [{:keys [row end-row]} (meta map-node)] (and row end-row (not= row end-row)))
                      (and (coll? v) (str/includes? (pprint-str v) "\n")))
        indent    (cond
                    (empty? entries) nil
                    (not multi?)     nil
                    key-col          (dec key-col)
                    (some #(= :newline (n/tag %)) prev-sep) (count (n/string (last prev-sep)))
                    :else            1)
        sep       (cond
                    (empty? entries) []
                    indent           [(n/newlines 1) (n/spaces indent)]
                    :else            [(n/spaces 1)])
        col       (when indent (+ indent 1 (count (pr-str k)) 1))]
    (n/replace-children map-node (into cs (concat sep [(n/coerce k) (n/spaces 1) (value-node v col)])))))

(defn- remove-entry
  "Removes `k`'s key and value nodes along with the layout that separated
   them from the previous entry (or, for the first entry, the layout up to
   the next one)."
  [map-node k]
  (let [cs      (vec (n/children map-node))
        entries (map-entries map-node)
        i       (first (keep-indexed (fn [i e] (when (= k (:key e)) i)) entries))
        e       (nth entries i)
        prev    (when (pos? i) (nth entries (dec i)))
        next    (when (< (inc i) (count entries)) (nth entries (inc i)))
        [from to] (cond
                    prev [(inc (:v prev)) (inc (:v e))]
                    next [(:k e) (:k next)]
                    :else [(:k e) (inc (:v e))])]
    (n/replace-children map-node (into (subvec cs 0 from) (subvec cs to)))))

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
          node    (reduce (fn [nd [rk ak]] (set-key-node nd rk ak)) node renames)
          node    (reduce (fn [nd k] (remove-entry nd k)) node (remove renames removed))
          node    (reduce (fn [nd k] (append-entry nd k (get new k)))
                          node (remove (set (vals renames)) added))]
      (reduce (fn [nd k]
                (let [ov (get old k) nv (get new k)]
                  (if (= ov nv) nd (set-value-node nd k (sync-node (get-node nd k) ov nv)))))
              node
              (filter (set new-ks) old-ks)))

    ;; a replaced leaf keeps the column of the node it replaces (parsed
    ;; nodes carry their position as metadata)
    :else (value-node new (:col (meta node)))))

(defn render
  "Returns `text` (the file the raw map `old` was read from) rewritten so it
   reads as `new`, preserving comments and layout wherever the value did not
   change. Falls back to a full pretty-print when `text` does not parse to
   `old` or the minimal rewrite does not round-trip."
  [text old new]
  (let [out (try
              (let [forms    (p/parse-string-all text)
                    children (vec (n/children forms))
                    idx      (first (keep-indexed (fn [i c] (when (map-node? c) i)) children))]
                (when (and idx (= old (n/sexpr (nth children idx))))
                  (let [out (n/string (n/replace-children
                                       forms
                                       (assoc children idx (sync-node (nth children idx) old new))))]
                    (when (= new (edn/read-string out)) out))))
              (catch Exception _ nil))]
    (or out (str (pprint-str new) "\n"))))
