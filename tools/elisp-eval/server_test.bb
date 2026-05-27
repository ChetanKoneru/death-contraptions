#!/usr/bin/env bb
(ns elisp-eval-server-test
  (:require [clojure.test :refer [deftest is testing]]
            [babashka.process :as process]))

(load-file "server.bb")

(def ^:private emacs-available?
  (try
    (let [proc (process/process ["emacsclient" "--eval" "t"]
                                {:out :string :err :string})
          completed? (.waitFor (:proc proc) 5 java.util.concurrent.TimeUnit/SECONDS)]
      (and completed? (zero? (:exit @proc))))
    (catch Exception _ false)))

(deftest truncate-test
  (testing "short string passes through"
    (is (= "hello" (truncate "hello"))))

  (testing "long string gets truncated with message"
    (let [long-str (apply str (repeat 60000 "x"))
          result   (truncate long-str)]
      (is (< (count result) (count long-str)))
      (is (re-find #"truncated 50000/60000 chars" result)))))

(deftest tool-schemas-valid-test
  (testing "all tools have name, description, inputSchema"
    (doseq [t [elisp-eval-tool screenshot-tool]]
      (is (string? (:name t)) (str "missing name in " t))
      (is (string? (:description t)) (str "missing description in " (:name t)))
      (is (map? (:inputSchema t)) (str "missing inputSchema in " (:name t)))
      (is (= "object" (get-in t [:inputSchema :type]))
          (str "inputSchema type not object in " (:name t))))))

(deftest handle-request-initialize-test
  (let [resp (handle-request {"id" 1 "method" "initialize" "params" {}})]
    (is (= 1 (:id resp)))
    (is (= "2.0" (:jsonrpc resp)))
    (is (= "2024-11-05" (get-in resp [:result :protocolVersion])))
    (is (= "elisp-eval" (get-in resp [:result :serverInfo :name])))))

(deftest handle-request-tools-list-test
  (let [resp (handle-request {"id" 2 "method" "tools/list" "params" {}})]
    (is (= 2 (:id resp)))
    (let [tool-list (get-in resp [:result :tools])]
      (is (= 2 (count tool-list)))
      (is (= #{"elisp-eval" "emacs-screenshot"}
             (set (map :name tool-list)))))))

(deftest screenshot-error-test
  (testing "returns error content map"
    (let [result (screenshot-error "something broke")]
      (is (= "something broke" (get-in result [:content 0 :text])))
      (is (true? (:isError result))))))

(deftest helpers-path-test
  (testing "helpers.el path resolves to existing file"
    (is (.exists (java.io.File. helpers-path)))))

(deftest helpers-loading-test
  (testing "ensure-helpers-loaded! is idempotent"
    (reset! helpers-loaded? false)
    (let [real-proc process/process
          call-count (atom 0)]
      (with-redefs [process/process (fn [_cmd & opts]
                                      (swap! call-count inc)
                                      (apply real-proc ["true"] opts))]
        (ensure-helpers-loaded!)
        (is (true? @helpers-loaded?))
        ;; second call is a no-op
        (ensure-helpers-loaded!)
        (is (true? @helpers-loaded?))
        (is (= 1 @call-count) "process invoked only on first call")))))

(deftest version-test
  (is (= "1.2.0" (:version server-info))))

(deftest tool-description-fields-test
  (testing "tool description documents structured response fields"
    (let [desc (:description elisp-eval-tool)]
      (is (re-find #"\[error\]" desc))
      (is (re-find #"\[backtrace\]" desc))
      (is (re-find #"\[messages\]" desc))
      (is (re-find #"\[warnings\]" desc))
      (is (re-find #"\[trace\]" desc)))))

;; Integration tests - require a running Emacs server

(deftest eval-success-test
  (when emacs-available?
    (testing "successful eval returns result in position 0"
      (let [resp (eval-elisp {"code" "(+ 1 2)"})]
        (is (nil? (:isError resp)))
        (is (= "3" (get-in resp [:content 0 :text])))))

    (testing "messages captured in [messages] block"
      (let [resp (eval-elisp {"code" "(progn (message \"eca-test-msg-%s\" 42) 99)"})
            texts (mapv :text (:content resp))]
        (is (= "99" (first texts)))
        (is (some #(and (re-find #"\[messages\]" %) (re-find #"eca-test-msg-42" %))
                  texts))))))

(deftest eval-error-structured-test
  (when emacs-available?
    (testing "error returns [error] block with condition type and message"
      (let [resp (eval-elisp {"code" "(error \"eca-test-kaboom\")"})
            err-block (get-in resp [:content 0 :text])]
        (is (true? (:isError resp)))
        (is (re-find #"\[error\]" err-block))
        (is (re-find #"error" err-block) "should contain condition type")
        (is (re-find #"eca-test-kaboom" err-block))))

    (testing "error includes [backtrace] block"
      (let [resp (eval-elisp {"code" "(error \"bt-test\")"})]
        (is (some #(re-find #"\[backtrace\]" (:text %))
                  (:content resp)))))))

(deftest eval-error-preserves-messages-test
  (when emacs-available?
    (testing "messages captured even when eval errors"
      (let [resp (eval-elisp {"code" "(progn (message \"eca-before-err\") (error \"fail\"))"})
            texts (mapv :text (:content resp))]
        (is (true? (:isError resp)))
        (is (re-find #"\[error\]" (first texts)))
        (is (some #(and (re-find #"\[messages\]" %) (re-find #"eca-before-err" %))
                  texts))))))

(deftest eval-warnings-test
  (when emacs-available?
    (testing "warnings captured in [warnings] block"
      (let [resp (eval-elisp {"code" "(progn (warn \"eca-test-warn-xyz\") t)"})
            texts (mapv :text (:content resp))]
        (is (nil? (:isError resp)))
        (is (some #(and (re-find #"\[warnings\]" %) (re-find #"eca-test-warn-xyz" %))
                  texts))))))
