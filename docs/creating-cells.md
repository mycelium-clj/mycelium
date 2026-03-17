# Creating Cells

Cells are the atomic units of Mycelium. Each cell is a pure function with explicit schema contracts.

## Cell Registration

Register cells via `defmethod` on the `cell/cell-spec` multimethod:

```clojure
(ns my-app.cells.auth
  (:require [mycelium.cell :as cell]))

(defmethod cell/cell-spec :auth/parse-request [_]
  {:id       :auth/parse-request
   :doc      "Extracts the auth token from the HTTP request body. Returns the token on success, or an error describing why the token is missing."
   :handler  (fn [_resources data]
               (let [body (get-in data [:http-request :body])]
                 (if-let [token (get body "auth-token")]
                   (assoc data :auth-token token)
                   (assoc data :error-type :missing-token
                               :error-message "No auth token provided"))))
   :schema   {:input  [:map [:http-request [:map [:body map?]]]]
              :output {:success [:map [:auth-token :string]]
                       :failure [:map [:error-type :keyword]
                                      [:error-message :string]]}}
   :requires []})
```

## Cell Spec Fields

| Field | Required | Description |
|-------|----------|-------------|
| `:id` | yes | Keyword identifier, conventionally `namespace/name` (e.g. `:auth/parse-request`) |
| `:doc` | yes | Non-empty string describing the cell's purpose and semantics — helps LLMs understand how the cell should be used |
| `:handler` | yes | `(fn [resources data] -> data)` for sync, or `(fn [resources data callback error-callback])` for async |
| `:schema` | yes | Map with `:input` (Malli schema) and `:output` (single schema or per-transition map) |
| `:requires` | no | Vector of resource keys the handler needs (e.g. `[:db]`) |
| `:async?` | no | Set to `true` for async handlers |

## Handler Signature

**Sync** (default):
```clojure
(fn [resources data] -> data)
```
- `resources`: map of injected dependencies (db, http-client, etc.)
- `data`: accumulating data map — all keys from prior cells are present
- Returns: the data map with new keys assoc'd

**Async** (`:async? true`):
```clojure
(fn [resources data callback error-callback])
```
- `callback`: call with `(callback updated-data)` on success
- `error-callback`: call with `(error-callback exception)` on failure

## Output Schema Formats

**Single schema** — all transitions must satisfy it:
```clojure
:output [:map [:result :int]]
```

**Per-transition schemas** — each dispatch transition has its own contract:
```clojure
:output {:found     [:map [:profile [:map [:name :string] [:email :string]]]]
         :not-found [:map [:error-type :keyword] [:error-message :string]]}
```

The transition keys in the output map must match the dispatch labels defined in the workflow's `:dispatches` for this cell.

## Rules

1. Cells must return the data map with computed values added (use `assoc`, not `select-keys`)
2. Output must satisfy the declared `:output` schema for the matched transition
3. Never import or call other cells — cells are isolated by design
4. Use only resources passed via the first argument — never acquire external dependencies directly
5. The `:id` used in `defmethod` must match the `:id` in the spec map

## Testing Cells in Isolation

```clojure
(require '[mycelium.dev :as dev])

;; Basic test
(dev/test-cell :auth/parse-request
  {:input     {:http-request {:body {"auth-token" "tok_abc"}}}
   :resources {}})
;; => {:pass? true, :output {...}, :errors [], :duration-ms 0.42}

;; With dispatch verification
(dev/test-cell :auth/parse-request
  {:input      {:http-request {:body {"auth-token" "tok_abc"}}}
   :resources  {}
   :dispatches [[:success (fn [d] (:auth-token d))]
                [:failure (fn [d] (:error-type d))]]
   :expected-dispatch :success})
;; => {:pass? true, :matched-dispatch :success, ...}
```

## Common Patterns

### Resource-dependent cell
```clojure
(defmethod cell/cell-spec :user/fetch-profile [_]
  {:id       :user/fetch-profile
   :doc      "Looks up a user profile by user-id from the database. Returns the profile on success, or a not-found error."
   :handler  (fn [{:keys [db]} data]
               (if-let [profile (db/get-user db (:user-id data))]
                 (assoc data :profile profile)
                 (assoc data :error-type :not-found
                             :error-message "User not found")))
   :schema   {:input  [:map [:user-id :string]]
              :output {:found     [:map [:profile [:map [:name :string] [:email :string]]]]
                       :not-found [:map [:error-type :keyword] [:error-message :string]]}}
   :requires [:db]})
```

### Async cell (external API call)
```clojure
(defmethod cell/cell-spec :api/fetch-data [_]
  {:id       :api/fetch-data
   :doc      "Fetches data from an external URL asynchronously. Returns the raw HTTP response."
   :handler  (fn [_resources data callback error-callback]
               (future
                 (try
                   (let [resp (http/get (:url data))]
                     (callback (assoc data :response resp)))
                   (catch Exception e
                     (error-callback e)))))
   :schema   {:input  [:map [:url :string]]
              :output [:map [:response map?]]}
   :async?   true})
```
