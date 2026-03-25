#!/usr/bin/env bb

(ns inject-behaviors
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; --- Debug logging ---

(def debug-enabled true)
(def debug-log-path "/tmp/ai-behaviors-debug.log")

(defn debug [& args]
  (when debug-enabled
    (let [ts (.format (java.time.LocalTime/now)
                      (java.time.format.DateTimeFormatter/ofPattern "HH:mm:ss"))
          msg (str "[" ts "] " (str/join " " args))]
      (spit debug-log-path (str msg "\n") :append true))))

;; --- Input parsing ---

(defn parse-input []
  (let [raw (slurp *in*)
        parsed (json/parse-string raw true)]
    {:prompt     (or (:prompt parsed) "")
     :session-id (or (:session_id parsed) "")
     :cwd        (or (:cwd parsed) "")}))

;; --- Path resolution ---

(defn resolve-repo-dir
  "Follow symlink on *file* to find repo root (parent of hooks/)."
  []
  (let [canonical (.getCanonicalPath (io/file *file*))
        hooks-dir (.getParent (io/file canonical))]
    (.getParent (io/file hooks-dir))))

(defn resolve-behavior-dir
  "Given a behavior name, find the first matching directory.
   Resolution order: project-local → user-local → repo.
   A valid dir must contain compose or prompt.md."
  [name {:keys [local-behaviors-dir user-behaviors-dir behaviors-dir]}]
  (let [valid? (fn [dir]
                 (when (and dir (.isDirectory (io/file dir)))
                   (let [d (io/file dir)]
                     (or (.exists (io/file d "compose"))
                         (.exists (io/file d "prompt.md"))))))]
    (cond
      (and local-behaviors-dir
           (valid? (str local-behaviors-dir "/" name)))
      (str local-behaviors-dir "/" name)

      (and user-behaviors-dir
           (valid? (str user-behaviors-dir "/" name)))
      (str user-behaviors-dir "/" name)

      (valid? (str behaviors-dir "/" name))
      (str behaviors-dir "/" name)

      :else nil)))

;; --- File reading (strip trailing newlines like bash's $(...)) ---

(defn slurp-trimmed
  "Read file, strip trailing newlines (matches bash $(cat ...) behavior)."
  [f]
  (str/trimr (slurp f)))

;; --- Composite expansion (recursive) ---

(defn expand-tags
  "Recursively expand composites to leaf behaviors.
   Returns {:leaves [tags] :missing [tags] :custom-texts {name content}}."
  [tags dirs depth seen]
  (when (>= depth 8)
    (binding [*out* *err*]
      (println (str "Nesting too deep (max depth 8)")))
    (System/exit 2))
  (reduce
   (fn [acc tag]
     (let [name (str/replace-first tag #"^#" "")
           dir  (resolve-behavior-dir name dirs)]
       (if-not dir
         (update acc :missing conj tag)
         (let [compose-file (io/file dir "compose")
               prompt-file  (io/file dir "prompt.md")]
           (if (.exists compose-file)
             ;; Composite: expand recursively
             (let [_ (when (contains? seen name)
                       (binding [*out* *err*]
                         (println (str "Cycle detected: " tag)))
                       (System/exit 2))
                   composed (str/trim (slurp compose-file))
                   _ (when (str/blank? composed)
                       (binding [*out* *err*]
                         (println (str "Empty compose file: " dir "/compose")))
                       (System/exit 2))
                   child-tags (str/split composed #"\s+")
                   sub (expand-tags child-tags dirs (inc depth) (conj seen name))
                   ;; If composite has its own prompt.md, capture as custom text
                   acc' (merge-with into acc sub)]
               (if (.exists prompt-file)
                 (assoc-in acc' [:custom-texts name] (slurp-trimmed prompt-file))
                 acc'))
             ;; Leaf behavior (has prompt.md, no compose)
             (if (.exists prompt-file)
               (update acc :leaves (fn [leaves]
                                     (if (some #{tag} leaves)
                                       leaves
                                       (conj leaves tag))))
               acc))))))
   {:leaves [] :missing [] :custom-texts {}}
   tags))

;; --- ASCII tree building (for #EXPLAIN) ---

(defn build-tree
  "Build ASCII expansion tree for a composite behavior."
  [name dirs prefix]
  (let [dir (resolve-behavior-dir name dirs)]
    (when (and dir (.exists (io/file dir "compose")))
      (let [composed (str/trim (slurp (io/file dir "compose")))
            items (str/split composed #"\s+")
            n (count items)]
        (apply str
               (map-indexed
                (fn [i item]
                  (let [last? (= i (dec n))
                        connector (if last? "└── " "├── ")
                        child-prefix (str prefix (if last? "    " "│   "))
                        child-name (str/replace-first item #"^#" "")
                        child-dir (resolve-behavior-dir child-name dirs)
                        subtree (when (and child-dir (.exists (io/file child-dir "compose")))
                                  (build-tree child-name dirs child-prefix))]
                    (str prefix connector item "\n" subtree)))
                items))))))

;; --- Hashtag extraction ---

(defn extract-hashtags
  "From prompt text, find the last tag line and extract unique hashtags.
   Anchors on #= (operating mode) first; fallback to any #hashtag line."
  [prompt]
  (let [lines (str/split-lines prompt)
        mode-line (->> lines
                       (filter #(re-find #"(^|[\s])#=[a-zA-Z0-9_-]+" %))
                       last)
        tag-line (or mode-line
                     (->> lines
                          (filter #(re-find #"(^|[\s])#[a-zA-Z0-9_-]+" %))
                          last))]
    (when tag-line
      (->> (re-seq #"(?:^|[\s])(#[=a-zA-Z0-9_-]+)" tag-line)
           (map (comp str/trim second))
           distinct
           vec))))

;; --- State persistence ---

(def state-dir (str (System/getProperty "user.home") "/.claude/behaviors-state"))

(defn state-file [session-id]
  (when (not (str/blank? session-id))
    (str state-dir "/" session-id)))

(defn read-state [session-id]
  (when-let [f (state-file session-id)]
    (let [file (io/file f)]
      (when (and (.exists file) (pos? (.length file)))
        (str/trim (slurp file))))))

(defn write-state! [session-id content]
  (when-let [f (state-file session-id)]
    (.mkdirs (io/file state-dir))
    (spit f (str content "\n"))))

(defn clear-state! [session-id]
  (when-let [f (state-file session-id)]
    (.mkdirs (io/file state-dir))
    (spit f "")))

;; --- HARD CONSTRAINT extraction ---

(defn extract-hard-constraints
  "Extract lines containing '-- HARD CONSTRAINT' from content, prefixed with tag."
  [tag content]
  (->> (str/split-lines content)
       (filter #(str/includes? % "-- HARD CONSTRAINT"))
       (remove str/blank?)
       (map #(str tag ": " %))))

;; --- Output assembly ---

(defn emit-json
  "Produce hookSpecificOutput JSON."
  [additional-context]
  (println
   (json/generate-string
    {:hookSpecificOutput
     {:hookEventName "UserPromptSubmit"
      :additionalContext additional-context}})))

(defn has-modifier-tags?
  "True if there are any non-mode tags (no #= prefix) in leaf tags."
  [leaf-tags]
  (some #(and (str/starts-with? % "#")
              (not (str/starts-with? % "#=")))
        leaf-tags))

(defn has-mode-tag?
  "True if there's a mode tag (#=...) in leaf tags."
  [leaf-tags]
  (some #(str/starts-with? % "#=") leaf-tags))

(defn marking-instruction [leaf-tags custom-texts]
  (when (or (has-modifier-tags? leaf-tags)
            (seq custom-texts))
    "\nWhen a behavior modifier causes you to make a point you would not otherwise make, mark it: (#name) after the sentence. Operating modes: no markers."))

(defn ask-tool-instruction [leaf-tags]
  (when (has-mode-tag? leaf-tags)
    "\nWhen you need to ask the user a question (clarification, decision, check-in, approval), use the AskUserQuestion tool. Do not write questions as plain text output and wait."))

;; --- Handle #CLEAR ---

(defn handle-clear [hashtags session-id]
  (let [others (remove #(= % "#CLEAR") hashtags)]
    (when (seq others)
      (binding [*out* *err*]
        (println "Conflict: #CLEAR cannot be combined with other behaviors."))
      (System/exit 2))
    (clear-state! session-id)
    (System/exit 0)))

;; --- Handle #EXPLAIN ---

(defn handle-explain [hashtags session-id dirs]
  (let [explain-tags (vec (remove #(= % "#EXPLAIN") hashtags))
        ;; No companions — read from state
        explain-tags (if (empty? explain-tags)
                       (if-let [state (read-state session-id)]
                         (str/split state #"\s+")
                         (do (binding [*out* *err*]
                               (println "No active behaviors to explain."))
                             (System/exit 2)))
                       explain-tags)
        ;; Expand composites
        {:keys [leaves missing custom-texts]} (expand-tags explain-tags dirs 0 #{})
        ;; Reject multiple operating modes
        modes (filter #(str/starts-with? % "#=") leaves)]
    (when (> (count modes) 1)
      (binding [*out* *err*]
        (println (str "Conflict: multiple operating modes: " (str/join " " modes) ". Use one at a time.")))
      (System/exit 2))

    ;; Build expansion trees for composite tags
    (let [trees (->> explain-tags
                     (keep (fn [tag]
                             (let [name (str/replace-first tag #"^#" "")
                                   dir (resolve-behavior-dir name dirs)]
                               (when (and dir (.exists (io/file dir "compose")))
                                 (str tag "\n" (build-tree name dirs ""))))))
                     (str/join "\n"))
          ;; Separate mode from modifiers
          mode-tag (first modes)
          mod-tags (remove #(str/starts-with? % "#=") leaves)
          ;; Build explain content
          explain-parts
          (concat
           ;; Mode
           (when mode-tag
             (let [name (str/replace-first mode-tag #"^#" "")
                   dir (resolve-behavior-dir name dirs)]
               (when (and dir (.exists (io/file dir "prompt.md")))
                 [(str "<behavior name=\"" mode-tag "\" role=\"mode\">\n"
                       (slurp-trimmed (io/file dir "prompt.md"))
                       "\n</behavior>")])))
           ;; Modifiers
           (keep (fn [tag]
                   (let [name (str/replace-first tag #"^#" "")
                         dir (resolve-behavior-dir name dirs)]
                     (when (and dir (.exists (io/file dir "prompt.md")))
                       (str "<behavior name=\"" tag "\" role=\"modifier\">\n"
                            (slurp-trimmed (io/file dir "prompt.md"))
                            "\n</behavior>"))))
                 mod-tags)
           ;; Custom texts
           (map (fn [[name content]]
                  (str "<behavior name=\"#" name "\" role=\"composite\">\n"
                       content
                       "\n</behavior>"))
                custom-texts))
          explain-content (str/join "\n" explain-parts)]

      (when (seq missing)
        (binding [*out* *err*]
          (println (str "Unknown behaviors:" (str/join " " missing)))))

      (let [tree-section (when (not (str/blank? trees))
                           (str "<expansion-tree>\n" trees "</expansion-tree>\n"))
            explain-output (when (or (not (str/blank? explain-content))
                                     (not (str/blank? tree-section)))
                             (str "<explain-instruction>\n"
                                  "Explain what this behavior combination would do. Do NOT follow these behaviors — analyze them.\n"
                                  "Be terse. Bullet points, not paragraphs. Plain language — no formal notation in output.\n"
                                  "If an expansion tree is provided, present it to show the user how composites compose into leaf behaviors.\n"
                                  "\n## Will do — obligations and actions, one bullet each.\n"
                                  "## Won't do — boundaries and exclusions.\n"
                                  "## Hard constraints — non-negotiable rules.\n"
                                  "## Interactions — how behaviors reinforce, tension, or scope each other. Only notable ones.\n"
                                  "## Example — brief: given a task, how would the response differ from default? Use the user's prompt as context if it contains a task, otherwise pick a hypothetical.\n"
                                  "</explain-instruction>\n"
                                  (or tree-section "")
                                  "<explain-behaviors>\n"
                                  explain-content
                                  "\n</explain-behaviors>"))]
        (when explain-output
          (emit-json explain-output))))
    (System/exit 0)))

;; --- Handle continuation (no hashtags, check state) ---

(defn handle-continuation [session-id dirs]
  (if-let [active (read-state session-id)]
    (let [active-tags (str/split active #"\s+")
          {:keys [leaves missing custom-texts]} (expand-tags active-tags dirs 0 #{})
          ;; Extract HARD CONSTRAINTs from leaf behaviors
          constraints
          (concat
           (mapcat (fn [tag]
                     (let [name (str/replace-first tag #"^#" "")
                           dir (resolve-behavior-dir name dirs)]
                       (when (and dir (.exists (io/file dir "prompt.md")))
                         (extract-hard-constraints tag (slurp-trimmed (io/file dir "prompt.md"))))))
                   leaves)
           (mapcat (fn [[name content]]
                     (extract-hard-constraints (str "#" name) content))
                   custom-texts))
          constraints-str (when (seq constraints)
                            (str/join "\n" constraints))
          marking (marking-instruction leaves custom-texts)
          ask-tool (ask-tool-instruction leaves)]
      (debug "continuation: ACTIVE=" active)
      (debug "continuation expanded: LEAF_TAGS=" (str/join " " leaves))
      (debug "continuation expanded: MISSING=" (str/join " " missing))
      (emit-json (str "Active: " active ". HARD CONSTRAINTs in force:"
                      (when constraints-str (str "\n" constraints-str))
                      (or marking "")
                      (or ask-tool ""))))
    ;; No state — nothing to inject
    nil))

;; --- Handle activation (main path: hashtags present) ---

(defn handle-activation [hashtags session-id dirs]
  (let [{:keys [leaves missing custom-texts]} (expand-tags hashtags dirs 0 #{})
        ;; Reject multiple operating modes
        modes (filter #(str/starts-with? % "#=") leaves)]
    (debug "expanded: LEAF_TAGS=" (str/join " " leaves))
    (debug "expanded: MISSING=" (str/join " " missing))

    (when (> (count modes) 1)
      (debug "CONFLICT: multiple modes:" (str/join " " modes))
      (binding [*out* *err*]
        (println (str "Conflict: multiple operating modes: " (str/join " " modes) ". Use one at a time.")))
      (System/exit 2))

    ;; Separate mode from modifiers
    (let [mode-tag (first modes)
          mod-tags (remove #(str/starts-with? % "#=") leaves)
          ;; Read mode content
          mode-context
          (when mode-tag
            (let [name (str/replace-first mode-tag #"^#" "")
                  dir (resolve-behavior-dir name dirs)]
              (when (and dir (.exists (io/file dir "prompt.md")))
                (slurp-trimmed (io/file dir "prompt.md")))))
          ;; Read modifier content
          mod-context
          (str/join "\n\n"
                    (concat
                     (keep (fn [tag]
                             (let [name (str/replace-first tag #"^#" "")
                                   dir (resolve-behavior-dir name dirs)]
                               (when (and dir (.exists (io/file dir "prompt.md")))
                                 (slurp-trimmed (io/file dir "prompt.md")))))
                           mod-tags)
                     ;; Append composite custom texts
                     (vals custom-texts)))]

      (debug "MODE_TAG=" (or mode-tag ""))
      (debug "MOD_TAGS=" (str/join " " mod-tags))

      ;; Report unknown behaviors
      (when (seq missing)
        (binding [*out* *err*]
          (println (str "Unknown behaviors:" (str/join " " missing)))))

      ;; Write state — original hashtags (pre-expansion), filtered to resolved
      (let [orig-modes (filter #(str/starts-with? % "#=") hashtags)
            orig-others (remove #(str/starts-with? % "#=") hashtags)
            active (->> (concat orig-modes orig-others)
                        (filter (fn [tag]
                                  (let [name (str/replace-first tag #"^#" "")]
                                    (resolve-behavior-dir name dirs))))
                        (str/join " "))]
        (write-state! session-id active)
        (debug "state written:" active))

      ;; Build structured output
      (let [wrapped (StringBuilder.)]
        (when (not (str/blank? mode-context))
          (.append wrapped (str "<operating-mode>\n" mode-context "\n</operating-mode>")))

        (when (not (str/blank? mod-context))
          (if (not (str/blank? mode-context))
            (.append wrapped (str "\n<behavior-modifiers>\n"
                                  "These modifiers apply WITHIN the operating mode's constraints. They NEVER relax or override HARD CONSTRAINTs.\n\n"
                                  mod-context
                                  "\n</behavior-modifiers>"))
            (.append wrapped (str "<behavior-modifiers>\n"
                                  mod-context
                                  "\n</behavior-modifiers>"))))

        ;; Marking instruction when modifiers are active
        (when (not (str/blank? mod-context))
          (.append wrapped "\nWhen a behavior modifier causes you to make a point you would not otherwise make, mark it: (#name) after the sentence. Operating modes: no markers."))

        ;; Ask tool instruction when a mode is active
        (when mode-tag
          (.append wrapped "\nWhen you need to ask the user a question (clarification, decision, check-in, approval), use the AskUserQuestion tool. Do not write questions as plain text output and wait."))

        (let [output (str wrapped)]
          (when (not (str/blank? output))
            (let [full-output (str output "\nThe above operating-mode and behavior-modifiers apply to all your responses until superseded. When new blocks appear, only the most recent set applies. During compaction, preserve the most recent <operating-mode> and <behavior-modifiers> blocks verbatim. Discard all older ones.")]
              (debug "output: injecting" (count full-output) "bytes")
              (emit-json full-output))))))))

;; --- Main ---

(defn -main []
  (let [{:keys [prompt session-id cwd]} (parse-input)
        repo-dir (resolve-repo-dir)
        behaviors-dir (str repo-dir "/behaviors")
        ;; Derive project-local behaviors dir
        project-root (when (not (str/blank? cwd))
                       (try
                         (let [proc (-> (ProcessBuilder. ["git" "-C" cwd "rev-parse" "--show-toplevel"])
                                        (.redirectErrorStream false)
                                        .start)
                               out (str/trim (slurp (.getInputStream proc)))]
                           (.waitFor proc)
                           (when (zero? (.exitValue proc))
                             out))
                         (catch Exception _ nil)))
        local-behaviors-dir (when project-root
                              (str project-root "/.ai-behaviors"))
        ;; User-local behaviors (XDG-compliant)
        xdg-config (or (System/getenv "XDG_CONFIG_HOME")
                       (str (System/getProperty "user.home") "/.config"))
        user-behaviors-dir (str xdg-config "/ai-behaviors/behaviors")
        dirs {:local-behaviors-dir local-behaviors-dir
              :user-behaviors-dir  user-behaviors-dir
              :behaviors-dir       behaviors-dir}]

    (debug "")
    (debug "--- new invocation ---")
    (debug "SESSION_ID=" session-id)
    (debug "PROMPT=" (subs prompt 0 (min 200 (count prompt))))

    ;; Empty prompt — exit
    (when (str/blank? prompt)
      (debug "empty prompt, exiting")
      (System/exit 0))

    ;; Extract hashtags
    (let [hashtags (extract-hashtags prompt)]
      (debug "HASHTAGS=" (str/join " " (or hashtags [])))

      ;; No hashtags — check state for continuation
      (when (or (nil? hashtags) (empty? hashtags))
        (debug "no hashtags in prompt, checking state")
        (handle-continuation session-id dirs)
        (System/exit 0))

      ;; Handle #CLEAR
      (when (some #(= % "#CLEAR") hashtags)
        (handle-clear hashtags session-id))

      ;; Handle #EXPLAIN
      (when (some #(= % "#EXPLAIN") hashtags)
        (handle-explain hashtags session-id dirs))

      ;; Main activation path
      (handle-activation hashtags session-id dirs))))

(-main)
