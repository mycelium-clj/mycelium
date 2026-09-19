(ns mycelium.cli-fixture-cells
  "Loaded via `myc --require` in CLI tests: registers handlers the fixtures
   reference so status/test/patch have something to run against."
  (:require [mycelium.cell :as cell]))

(defmethod cell/cell-spec :fixture/double [_]
  {:id      :fixture/double
   :handler (fn [_ data] {:y (* 2 (:x data))})
   :schema  {:input  [:map [:x :int]]
             :output [:map [:y :int]]}})

(defmethod cell/cell-spec :fixture/broken [_]
  {:id      :fixture/broken
   :handler (fn [_ _] {:y "not an int"})
   :schema  {:input  [:map [:x :int]]
             :output [:map [:y :int]]}})
