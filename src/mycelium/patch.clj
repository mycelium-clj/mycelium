(ns mycelium.patch
  "Checked manifest edits — the manifest-editing analog of a compiler patch.
   Ops rewrite every reference to a cell across the manifest, an optional
   expect-hash guard rejects stale edits, and the result is validated with
   the full manifest validator before the caller ever sees it."
  (:require [mycelium.manifest :as manifest]))

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
  "Deterministic sha-256 hex of a manifest, insensitive to key order and
   whitespace. Dispatch predicate forms hash as structure, not functions."
  [m]
  (let [bytes (.digest (java.security.MessageDigest/getInstance "SHA-256")
                       (.getBytes (pr-str (canonical-form m)) "UTF-8"))]
    (apply str (map #(format "%02x" %) bytes))))

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

(defn- rename-cell*
  "Rewrites every reference to `from` across all manifest sections."
  [{:keys [cells edges dispatches joins regions constraints timeouts
           resilience error-groups pipeline] :as m}
   from to]
  (let [cell?      (contains? cells from)
        join-name? (contains? (or joins {}) from)]
    (cond
      (not (or cell? join-name?))
      (throw (ex-info (str "Unknown cell " from " — not found in :cells or :joins")
                      {:cell from :available (set (concat (keys cells) (keys (or joins {}))))}))

      (or (contains? cells to) (contains? (or joins {}) to))
      (throw (ex-info (str "Cannot rename " from " to " to " — " to " already exists")
                      {:from from :to to}))

      (= from :start)
      (throw (ex-info "Cannot rename :start — reachability is BFS-rooted at :start"
                      {:from from}))

      :else
      (let [update-vec (fn [v] (mapv #(rename-kw from to %) v))]
        (cond-> m
          cells        (update :cells rename-map-keys from to)
          ;; cell-level :on-error targets
          cells        (update :cells
                               (fn [cs] (into {}
                                              (map (fn [[k def]]
                                                     [k (if (map? def)
                                                          (update def :on-error #(when % (rename-kw from to %)))
                                                          def)]))
                                              cs)))
          edges        (update :edges
                               (fn [es] (into {}
                                              (map (fn [[k v]]
                                                     [(rename-kw from to k) (rename-edge-def from to v)]))
                                              es)))
          dispatches   (update :dispatches rename-map-keys from to)
          joins        (update :joins
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
          pipeline     (update :pipeline update-vec))))))

;; ===== op application =====

(defn apply-op
  "Applies a single op map to a manifest. Supported ops:
     {:op \"rename-cell\" :from :old :to :new}
   The result is re-validated with validate-manifest before returning."
  [m {:keys [op from to] :as _op}]
  (case op
    "rename-cell" (let [result (rename-cell* m from to)]
                    (manifest/validate-manifest result {:strict? false})
                    result)
    (throw (ex-info (str "Unknown op: " op ". Supported: rename-cell") {:op op}))))

(defn apply-ops
  "Applies ops sequentially with an optional expect-hash guard.
   Opts: {:expect-hash \"...\" :ops [{...} ...]}"
  [m {:keys [expect-hash ops]}]
  (when expect-hash
    (let [current (manifest-hash m)]
      (when (not= expect-hash current)
        (throw (ex-info (str "Stale manifest: expected hash " expect-hash
                             " but current hash: " current
                             " — re-read the manifest and re-apply")
                        {:expected expect-hash :current current})))))
  (reduce apply-op m ops))
