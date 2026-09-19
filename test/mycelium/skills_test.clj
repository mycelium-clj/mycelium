(ns mycelium.skills-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [mycelium.cli :as cli]))

(deftest skills-lists-bundled-topics-test
  (let [{:keys [exit message]} (cli/run ["skills"])]
    (is (zero? exit))
    (doseq [topic ["agent" "manifest" "cells" "testing" "patterns"]]
      (is (str/includes? message topic)))
    ;; each topic line carries its served size and frontmatter description
    (is (re-find #"agent\s+\(~[0-9.]+ KB\) .*loop" message))
    (is (re-find #"manifest\s+\(~[0-9.]+ KB\) .*EDN" message))))

(deftest skills-get-returns-frontmatter-free-content-test
  (let [{:keys [exit message]} (cli/run ["skills" "get" "agent"])]
    (is (zero? exit))
    (is (str/starts-with? (str/triml message) "# "))
    (is (not (str/includes? message "---\n")))
    (is (str/includes? message "myc"))))

(deftest skills-get-section-extracts-one-heading-test
  (let [{:keys [exit message]} (cli/run ["skills" "get" "agent" "--section" "edit-loop"])]
    (is (zero? exit))
    ;; the section starts with its own heading and stops at the next
    ;; heading of the same or higher level
    (is (str/starts-with? message "## Edit Loop"))
    (is (not (str/includes? message "## Rules")))
    (is (not (str/includes? message "## Commands")))
    (is (< (count message) (count (:message (cli/run ["skills" "get" "agent"])))))))

(deftest skills-section-flag-position-does-not-matter-test
  (let [a (cli/run ["skills" "get" "agent" "--section" "rules"])
        b (cli/run ["skills" "get" "--section" "rules" "agent"])]
    (is (zero? (:exit a)))
    (is (= (:message a) (:message b)))))

(deftest skills-section-stops-at-higher-level-heading-test
  (let [extract (var-get #'cli/extract-section)
        doc "# Top\n\n## A {#a}\n\nbody a\n\n### A.1\n\nsub\n\n## B {#b}\n\nbody b\n"]
    (is (= "## A {#a}\n\nbody a\n\n### A.1\n\nsub" (extract doc "a")))
    (is (= "## B {#b}\n\nbody b" (extract doc "b")))))

(deftest skills-get-unknown-topic-exits-1-test
  (let [{:keys [exit message]} (cli/run ["skills" "get" "nope"])]
    (is (= 1 exit))
    (is (str/includes? message "Unknown skill topic"))
    (is (str/includes? message "agent"))))

(deftest skills-get-unknown-section-exits-1-test
  (let [{:keys [exit message]} (cli/run ["skills" "get" "agent" "--section" "zzz"])]
    (is (= 1 exit))
    (is (str/includes? message "Unknown section"))))

(deftest skills-get-manifest-topic-describes-edn-test
  (let [{:keys [exit message]} (cli/run ["skills" "get" "manifest"])]
    (is (zero? exit))
    (is (str/includes? message ":cells"))
    (is (str/includes? message ":edges"))))
