#!/usr/bin/env bb
;; MCP server for elisp evaluation via emacsclient.
;; Writes code to a temp file and evals it - no shell escaping issues.
;; Author: Ag Ibragimov - github.com/agzam

(require '[cheshire.core :as json]
         '[babashka.process :as process]
         '[clojure.string :as str])

(import '[java.io File]
        '[java.util.concurrent TimeUnit]
        '[java.nio.file Files])

(def ^:private default-timeout-sec 30)
(def ^:private max-output-chars 50000)
(def ^:private helpers-loaded? (atom false))

(def ^:private helpers-path
  "Absolute path to helpers.el, resolved relative to this script."
  (let [script-dir (if-let [bf (System/getProperty "babashka.file")]
                     (-> (java.io.File. bf)
                         (.getParentFile)
                         (.getAbsolutePath))
                     (System/getProperty "user.dir"))]
    (str script-dir "/helpers.el")))

(defn- ensure-helpers-loaded!
  "Load helpers.el into Emacs on first eval call."
  []
  (when (compare-and-set! helpers-loaded? false true)
    (let [proc (process/process
                ["emacsclient" "--eval"
                 (format "(load \"%s\" t t)" helpers-path)]
                {:out :string :err :string})]
      (.waitFor (:proc proc) 5 TimeUnit/SECONDS))))

(defn- truncate
  "Emacsclient output can be enormous (e.g., full buffer dumps); cap it to
  avoid blowing up MCP message size limits."
  [s]
  (if (< max-output-chars (count s))
    (str (subs s 0 max-output-chars)
         (format "\n[truncated %d/%d chars]"
                 max-output-chars (count s)))
    s))

(def server-info
  {:name "elisp-eval" :version "1.2.0"})

(def elisp-eval-tool
  {:name "elisp-eval"
   :description "Evaluate Emacs Lisp in the running Emacs server. Returns result, *Messages*, and *trace-output*. State persists between calls. Only last expression's return value is captured. No escaping needed. Helpers auto-loaded: eca--check-parens-file, eca--byte-compile-check, eca--file-defuns.\n\nResponse fields (separate content blocks):\n- Position 0: eval result (success) or [error] with condition type and message\n- [backtrace]: stack trace on error (Emacs 29+ only)\n- [messages]: new *Messages* buffer output during eval\n- [warnings]: new *Warnings* buffer output during eval\n- [trace]: new *trace-output* buffer output during eval"
   :inputSchema
   {:type "object"
    :properties {:code {:type "string"
                        :description "Emacs Lisp code to evaluate."}
                 :timeout {:type "integer"
                           :description "Timeout in seconds (default: 30)."}
                 :print_length {:type "integer"
                                :description "Max list/vector elements to print (default: 200). Set to -1 for unlimited."}
                 :print_level {:type "integer"
                               :description "Max nesting depth to print (default: 10). Set to -1 for unlimited."}}
    :required ["code"]}})

(def screenshot-tool
  {:name "emacs-screenshot"
   :description "Capture a screenshot of the current Emacs frame as a PNG image. macOS only."
   :inputSchema
   {:type "object"
    :properties {}
    :required []}})

(defn eval-elisp
  "Core elisp evaluation via emacsclient. Writes code to a temp file to avoid
  shell-escaping issues, wraps it to capture Messages/trace/backtrace, and
  enforces a timeout to kill runaway or interactive evals."
  [{:strs [code timeout print_length print_level]}]
  (ensure-helpers-loaded!)
  (let [timeout-sec (or timeout default-timeout-sec)
        pl  (if print_length (if (= print_length -1) "nil" (str print_length)) "200")
        plv (if print_level  (if (= print_level -1)  "nil" (str print_level))  "10")
        tmp        (File/createTempFile "eca-elisp-"  ".el")
        msgs-tmp   (File/createTempFile "eca-msgs-"   ".txt")
        bt-tmp     (File/createTempFile "eca-bt-"     ".txt")
        trace-tmp  (File/createTempFile "eca-trace-"  ".txt")
        result-tmp   (File/createTempFile "eca-result-"   ".txt")
        error-tmp    (File/createTempFile "eca-error-"    ".txt")
        warnings-tmp (File/createTempFile "eca-warnings-" ".txt")
        path          (.getAbsolutePath tmp)
        msgs-path     (.getAbsolutePath msgs-tmp)
        bt-path       (.getAbsolutePath bt-tmp)
        trace-path    (.getAbsolutePath trace-tmp)
        result-path   (.getAbsolutePath result-tmp)
        error-path    (.getAbsolutePath error-tmp)
        warnings-path (.getAbsolutePath warnings-tmp)]
    (try
      (spit tmp code)
      (let [wrapper (format "(let* ((eca--bt-path \"%s\")
       (eca--result-path \"%s\")
       (eca--trace-path \"%s\")
       (eca--error-path \"%s\")
       (eca--warnings-path \"%s\")
       (msgs-buf (get-buffer-create \"*Messages*\"))
       (msgs-pos (with-current-buffer msgs-buf (point-max)))
       (trace-buf (get-buffer \"*trace-output*\"))
       (trace-pos (when trace-buf (with-current-buffer trace-buf (point-max))))
       (warn-buf (get-buffer \"*Warnings*\"))
       (warn-pos (when warn-buf (with-current-buffer warn-buf (point-max))))
       (eca--error-info nil)
       (result (with-temp-buffer
                 (insert-file-contents \"%s\")
                 (goto-char (point-min))
                 (let (forms)
                   (condition-case nil
                       (while t (push (read (current-buffer)) forms))
                     (end-of-file nil))
                   (let ((eca--code (cons 'progn (nreverse forms))))
                     (condition-case err
                         (if (fboundp 'handler-bind)
                             (handler-bind
                                 ((error
                                   (lambda (_e)
                                     (write-region
                                      (with-output-to-string (backtrace))
                                      nil eca--bt-path nil 'silent))))
                               (eval eca--code t))
                           (eval eca--code t))
                       (error
                        (setq eca--error-info
                              (format \"%%S: %%s\" (car err) (error-message-string err)))
                        nil))))))
       (new-msgs (with-current-buffer msgs-buf
                   (let ((s (string-trim (buffer-substring-no-properties msgs-pos (point-max)))))
                     (and (not (string-empty-p s)) s))))
       (new-trace (let ((tb (or trace-buf (get-buffer \"*trace-output*\"))))
                    (when tb
                      (with-current-buffer tb
                        (let ((s (string-trim
                                  (buffer-substring-no-properties
                                   (or trace-pos (point-min)) (point-max)))))
                          (and (not (string-empty-p s)) s))))))
       (new-warnings (let ((wb (or warn-buf (get-buffer \"*Warnings*\"))))
                       (when wb
                         (with-current-buffer wb
                           (let ((s (string-trim
                                     (buffer-substring-no-properties
                                      (or warn-pos (point-min)) (point-max)))))
                             (and (not (string-empty-p s)) s)))))))
  (when new-msgs
    (write-region new-msgs nil \"%s\" nil 'silent))
  (when new-trace
    (write-region new-trace nil eca--trace-path nil 'silent))
  (when new-warnings
    (write-region new-warnings nil eca--warnings-path nil 'silent))
  (if eca--error-info
      (progn
        (write-region eca--error-info nil eca--error-path nil 'silent)
        nil)
    (let ((print-length %s)
          (print-level %s))
      (write-region (prin1-to-string result) nil eca--result-path nil 'silent)
      result)))" bt-path result-path trace-path error-path warnings-path path msgs-path pl plv)
            proc (process/process ["emacsclient" "--eval" wrapper]
                                  {:out :string :err :string})
            completed? (.waitFor (:proc proc) timeout-sec TimeUnit/SECONDS)]
        (if-not completed?
          (do (.destroyForcibly (:proc proc))
              {:content [{:type "text"
                          :text (format "Timed out (%ds). Likely interactive prompt or infinite loop. Process killed."
                                        timeout-sec)}]
               :isError true})
          (let [{:keys [exit out err]} @proc
                result-text (let [s (str/trim (slurp result-tmp))]
                              (when-not (str/blank? s) s))
                result    (or result-text (str/trim out))
                messages  (let [s (str/trim (slurp msgs-tmp))]
                            (when-not (str/blank? s) s))
                backtrace (let [s (str/trim (slurp bt-tmp))]
                            (when-not (str/blank? s) s))
                trace     (let [s (str/trim (slurp trace-tmp))]
                            (when-not (str/blank? s) s))
                err-text  (let [s (str/trim (slurp error-tmp))]
                            (when-not (str/blank? s) s))
                warnings  (let [s (str/trim (slurp warnings-tmp))]
                            (when-not (str/blank? s) s))]
            (cond
              ;; Structured error: eval signaled a condition
              err-text
              {:content (cond-> [{:type "text" :text (truncate (str "[error]\n" err-text))}]
                          backtrace (conj {:type "text" :text (truncate (str "[backtrace]\n" backtrace))})
                          messages  (conj {:type "text" :text (truncate (str "[messages]\n" messages))})
                          warnings  (conj {:type "text" :text (truncate (str "[warnings]\n" warnings))})
                          trace     (conj {:type "text" :text (truncate (str "[trace]\n" trace))}))
               :isError true}

              ;; Success
              (zero? exit)
              {:content (cond-> [{:type "text" :text (truncate result)}]
                          messages (conj {:type "text" :text (truncate (str "[messages]\n" messages))})
                          warnings (conj {:type "text" :text (truncate (str "[warnings]\n" warnings))})
                          trace    (conj {:type "text" :text (truncate (str "[trace]\n" trace))}))}

              ;; Fallback: wrapper-level failure
              :else
              {:content [{:type "text" :text (truncate (str/trim (str out err)))}]
               :isError true}))))
      (finally
        (.delete tmp)
        (.delete msgs-tmp)
        (.delete bt-tmp)
        (.delete trace-tmp)
        (.delete result-tmp)
        (.delete error-tmp)
        (.delete warnings-tmp)))))

(defn- emacsclient-eval
  "Quick one-shot emacsclient eval. Returns trimmed stdout or nil."
  ([expr] (emacsclient-eval expr 5))
  ([expr timeout-sec]
   (let [proc (process/process ["emacsclient" "--eval" expr]
                               {:out :string :err :string})
         completed? (.waitFor (:proc proc) timeout-sec TimeUnit/SECONDS)]
     (when (and completed? (zero? (:exit @proc)))
       (str/trim (:out @proc))))))

(defn- file->b64-image
  "Read a PNG file, return MCP image content."
  [^File f]
  (let [bytes (Files/readAllBytes (.toPath f))
        b64   (.encodeToString (java.util.Base64/getEncoder) bytes)]
    {:content [{:type "image" :data b64 :mimeType "image/png"}]}))

(defn- screenshot-error
  "Standardized MCP error response for screenshot failures - avoids repeating
  the error-wrapping boilerplate in every platform-specific screenshot fn."
  [msg]
  {:content [{:type "text" :text msg}] :isError true})

(defn- screenshot-via-emacs
  "Run a screenshot command as an Emacs child process so it inherits Emacs.app's
  macOS screen-recording / accessibility TCC grants. Returns the exit code
  string from emacsclient, or nil on failure."
  [elisp]
  (emacsclient-eval elisp 15))

(defn- screenshot-macos
  "Capture Emacs frame via screencapture, routed through Emacs to inherit
  macOS screen recording permission from Emacs.app. Uses eca--frame-cg-window-id
  helper to find the CGWindowID via CoreGraphics."
  []
  (let [tmp      (File/createTempFile "emacs-screenshot-" ".png")
        tmp-path (.getAbsolutePath tmp)]
    (try
      (let [result (screenshot-via-emacs
                    (format "(let ((wid (eca--frame-cg-window-id)))
                               (if (string-empty-p wid)
                                   -1
                                 (call-process \"screencapture\" nil nil nil
                                               (concat \"-l\" wid) \"-x\" \"-o\" \"%s\")))"
                            tmp-path))]
        (cond
          (nil? result)
          (screenshot-error "emacsclient failed. Is Emacs server running?")

          (= result "0")
          (file->b64-image tmp)

          :else
          (screenshot-error "screencapture failed. Is Emacs a GUI window?")))
      (finally (.delete tmp)))))

(defn- screenshot-x11
  "Capture Emacs frame via ImageMagick import, routed through Emacs."
  []
  (let [tmp      (File/createTempFile "emacs-screenshot-" ".png")
        tmp-path (.getAbsolutePath tmp)]
    (try
      (let [result (screenshot-via-emacs
                    (format "(let ((wid (frame-parameter nil 'outer-window-id)))
                               (if (and wid (not (string-empty-p wid)))
                                   (call-process \"import\" nil nil nil
                                                 \"-window\" wid \"%s\")
                                 -1))"
                            tmp-path))]
        (cond
          (nil? result)
          (screenshot-error "emacsclient failed.")

          (= result "0")
          (file->b64-image tmp)

          :else
          (screenshot-error "import failed. Is ImageMagick installed?")))
      (finally (.delete tmp)))))

(defn- screenshot-pgtk
  "Wayland screenshot via gnome-screenshot or grim, routed through Emacs."
  []
  (let [tmp      (File/createTempFile "emacs-screenshot-" ".png")
        tmp-path (.getAbsolutePath tmp)]
    (try
      (let [result (screenshot-via-emacs
                    (format "(cond
                               ((zerop (call-process \"gnome-screenshot\" nil nil nil
                                                      \"-w\" \"-f\" \"%s\")) 0)
                               ((zerop (call-process \"grim\" nil nil nil \"%s\")) 0)
                               (t 1))"
                            tmp-path tmp-path))]
        (cond
          (nil? result)
          (screenshot-error "emacsclient failed.")

          (= result "0")
          (file->b64-image tmp)

          :else
          (screenshot-error "gnome-screenshot and grim both failed.")))
      (finally (.delete tmp)))))

(defn take-screenshot
  "Dispatch to the correct platform screenshot method based on Emacs window
  system (ns/x/pgtk), since each requires different capture tooling."
  []
  (let [ws (emacsclient-eval "(framep (selected-frame))")]
    (case ws
      "ns"   (screenshot-macos)
      "x"    (screenshot-x11)
      "pgtk" (screenshot-pgtk)
      (screenshot-error
       (str "Unsupported window system: " ws ". Requires GUI Emacs.")))))

(defn handle-request
  "MCP JSON-RPC dispatch - routes initialize, tools/list, and tools/call to
  their handlers; returns nil for notifications and unknown methods."
  [{:strs [id method params]}]
  (case method
    "initialize"
    {:jsonrpc "2.0" :id id
     :result {:protocolVersion "2024-11-05"
              :capabilities {:tools {}}
              :serverInfo server-info}}
    
    "notifications/initialized" nil
    
    "tools/list"
    {:jsonrpc "2.0" :id id
     :result {:tools [elisp-eval-tool screenshot-tool]}}
    
    "tools/call"
    (let [{tool "name" args "arguments"} params]
      {:jsonrpc "2.0" :id id
       :result (case tool
                 "elisp-eval"       (eval-elisp args)
                 "emacs-screenshot" (take-screenshot)
                 {:content [{:type "text" :text (str "Unknown tool: " tool)}]
                  :isError true})})
    
    ;; Unknown method - ignore
    nil))

(when (= *file* (System/getProperty "babashka.file"))
  (doseq [line (line-seq (java.io.BufferedReader. *in*))]
    (when-not (str/blank? line)
      (when-let [res (handle-request (json/parse-string line))]
        (println (json/generate-string res))
        (flush)))))
