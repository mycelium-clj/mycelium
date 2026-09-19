(ns mycelium.skills-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [mycelium.cli :as cli]))

(deftest skills-lists-bundled-topics-test
  (let [{:keys [exit message]} (cli/run ["skills"])]
    (is (zero? exit))
    (doseq [topic ["agent" "manifest" "cells" "testing" "patterns"]]
      (is (str/includes? message topic)))))

(deftest skills-get-returns-frontmatter-free-content-test
  (let [{:keys [exit message]} (cli/run ["skills" "get" "agent"])]
    (is (zero? exit))
    (is (str/starts-with? (str/triml message) "# "))
    (is (not (str/includes? message "---\n")))
    (is (str/includes? message "myc"))))

(deftest skills-get-section-extracts-one-heading-test
  (let [{:keys [exit message]} (cli/run ["skills" "get" "agent" "--section" "edit-loop"])]
    (is (zero? exit))
    (is (str/includes? message "## "))
    ;; section fetch is strictly smaller than the whole topic
    (is (< (count message) (count (:message (cli/run ["skills" "get" "agent"])))))
    (is (not (str/includes? message "## Verify")))))

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
