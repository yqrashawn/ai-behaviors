#!/usr/bin/env bb

(ns inject-behaviors-test
  (:require [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; --- Test infrastructure ---

(def repo-dir (.getCanonicalPath (io/file (str (.getParent (io/file *file*)) "/.."))))
(def bb-script (str repo-dir "/hooks/inject-behaviors.bb"))
(def sh-script (str repo-dir "/hooks/inject-behaviors.sh"))
(def state-dir (str (System/getProperty "user.home") "/.claude/behaviors-state"))
(def cwd repo-dir)

(def ^:dynamic *test-counter* (atom 0))
(def ^:dynamic *pass-count* (atom 0))
(def ^:dynamic *fail-count* (atom 0))
(def ^:dynamic *failures* (atom []))

(defn fresh-session []
  (str "test-e2e-" (swap! *test-counter* inc) "-" (System/currentTimeMillis)))

(defn run-script
  "Run a script with given JSON input. Returns {:stdout :stderr :exit}."
  [script input-map]
  (let [input-json (json/generate-string input-map)
        result (p/shell {:in input-json :out :string :err :string
                         :continue true}
                        script)]
    {:stdout (:out result)
     :stderr (:err result)
     :exit   (:exit result)}))

(defn run-bb [input-map]
  (run-script bb-script input-map))

(defn run-sh [input-map]
  (run-script sh-script input-map))

(defn parse-context
  "Extract additionalContext from hook JSON output."
  [stdout]
  (when (not (str/blank? stdout))
    (-> (json/parse-string stdout true)
        (get-in [:hookSpecificOutput :additionalContext]))))

(defn read-state-file [session-id]
  (let [f (io/file state-dir session-id)]
    (when (.exists f)
      (str/trim (slurp f)))))

;; --- Assertion helpers ---

(defn pass! [test-name]
  (swap! *pass-count* inc)
  (println (str "  PASS  " test-name)))

(defn fail! [test-name reason]
  (swap! *fail-count* inc)
  (swap! *failures* conj {:name test-name :reason reason})
  (println (str "  FAIL  " test-name))
  (println (str "        " reason)))

(defn assert-eq [test-name expected actual]
  (if (= expected actual)
    (pass! test-name)
    (fail! test-name (str "expected: " (pr-str expected) "\n        got:      " (pr-str actual)))))

(defn assert-true [test-name val]
  (if val
    (pass! test-name)
    (fail! test-name "expected truthy, got falsy")))

(defn assert-false [test-name val]
  (if (not val)
    (pass! test-name)
    (fail! test-name (str "expected falsy, got: " (pr-str val)))))

(defn assert-contains [test-name haystack needle]
  (if (and haystack (str/includes? haystack needle))
    (pass! test-name)
    (fail! test-name (str "expected to contain: " (pr-str needle)
                          "\n        in: " (pr-str (when haystack (subs haystack 0 (min 200 (count haystack)))))))))

(defn assert-not-contains [test-name haystack needle]
  (if (or (nil? haystack) (not (str/includes? haystack needle)))
    (pass! test-name)
    (fail! test-name (str "expected NOT to contain: " (pr-str needle)))))

(defn assert-match-sh
  "Assert bb output matches sh output for the same input (different session IDs)."
  [test-name input-base]
  (let [bb-sid (fresh-session)
        sh-sid (fresh-session)
        bb-result (run-bb (assoc input-base :session_id bb-sid))
        sh-result (run-sh (assoc input-base :session_id sh-sid))
        bb-ctx (parse-context (:stdout bb-result))
        sh-ctx (parse-context (:stdout sh-result))]
    (if (= bb-ctx sh-ctx)
      (pass! (str test-name " [bb=sh]"))
      (fail! (str test-name " [bb=sh]")
             (str "bb and sh differ.\n"
                  "        bb exit=" (:exit bb-result) " sh exit=" (:exit sh-result) "\n"
                  "        bb ctx[0:150]=" (when bb-ctx (subs bb-ctx 0 (min 150 (count bb-ctx)))) "\n"
                  "        sh ctx[0:150]=" (when sh-ctx (subs sh-ctx 0 (min 150 (count sh-ctx)))))))))

;; --- Test groups ---

(defn test-empty-prompt []
  (println "\n== Empty prompt ==")
  (let [sid (fresh-session)
        result (run-bb {:prompt "" :session_id sid :cwd cwd})]
    (assert-eq "exit 0" 0 (:exit result))
    (assert-true "no stdout" (str/blank? (:stdout result)))))

(defn test-no-hashtags-no-state []
  (println "\n== No hashtags, no state ==")
  (let [sid (fresh-session)
        result (run-bb {:prompt "just a regular prompt" :session_id sid :cwd cwd})]
    (assert-eq "exit 0" 0 (:exit result))
    (assert-true "no stdout" (str/blank? (:stdout result)))))

(defn test-mode-only []
  (println "\n== Mode only ==")
  (let [sid (fresh-session)
        result (run-bb {:prompt "fix it #=code" :session_id sid :cwd cwd})
        ctx (parse-context (:stdout result))]
    (assert-eq "exit 0" 0 (:exit result))
    (assert-contains "has operating-mode" ctx "<operating-mode>")
    (assert-contains "has #=code content" ctx "#=code")
    (assert-not-contains "no behavior-modifiers section" ctx "<behavior-modifiers>\n")
    (assert-contains "has ask-tool instruction" ctx "AskUserQuestion")
    (assert-contains "has compaction instruction" ctx "During compaction"))
  (assert-match-sh "mode only" {:prompt "fix it #=code" :cwd cwd}))

(defn test-mode-plus-modifier []
  (println "\n== Mode + modifier ==")
  (let [sid (fresh-session)
        result (run-bb {:prompt "fix bug #=debug #deep" :session_id sid :cwd cwd})
        ctx (parse-context (:stdout result))]
    (assert-eq "exit 0" 0 (:exit result))
    (assert-contains "has operating-mode" ctx "<operating-mode>")
    (assert-contains "has behavior-modifiers" ctx "<behavior-modifiers>")
    (assert-contains "has HARD CONSTRAINT preamble" ctx "They NEVER relax or override HARD CONSTRAINTs")
    (assert-contains "has marking instruction" ctx "(#name) after the sentence")
    (assert-contains "has ask-tool instruction" ctx "AskUserQuestion"))
  (assert-match-sh "mode+modifier" {:prompt "fix bug #=debug #deep" :cwd cwd}))

(defn test-modifiers-only []
  (println "\n== Modifiers only (no mode) ==")
  (let [sid (fresh-session)
        result (run-bb {:prompt "think about this #deep #challenge" :session_id sid :cwd cwd})
        ctx (parse-context (:stdout result))]
    (assert-eq "exit 0" 0 (:exit result))
    (assert-not-contains "no operating-mode section" ctx "<operating-mode>\n")
    (assert-contains "has behavior-modifiers" ctx "<behavior-modifiers>")
    (assert-not-contains "no HARD CONSTRAINT preamble" ctx "They NEVER relax")
    (assert-contains "has marking instruction" ctx "(#name) after the sentence")
    (assert-not-contains "no ask-tool (no mode)" ctx "AskUserQuestion"))
  (assert-match-sh "modifiers only" {:prompt "think about this #deep #challenge" :cwd cwd}))

(defn test-composite-expansion []
  (println "\n== Composite expansion ==")
  ;; #code = #=code #g #io #ask-tool (compose file)
  (let [sid (fresh-session)
        result (run-bb {:prompt "implement it #code" :session_id sid :cwd cwd})
        ctx (parse-context (:stdout result))]
    (assert-eq "exit 0" 0 (:exit result))
    (assert-contains "has operating-mode (from #=code)" ctx "<operating-mode>")
    (assert-contains "has behavior-modifiers (from #g expansion)" ctx "<behavior-modifiers>")
    ;; #g expands to many leaf modifiers including #deep
    (assert-contains "has #deep content (from #g)" ctx "#deep"))
  (assert-match-sh "composite" {:prompt "implement it #code" :cwd cwd}))

(defn test-composite-plus-extra []
  (println "\n== Composite + extra modifier ==")
  (let [sid (fresh-session)
        result (run-bb {:prompt "do it #code #challenge" :session_id sid :cwd cwd})
        ctx (parse-context (:stdout result))]
    (assert-eq "exit 0" 0 (:exit result))
    (assert-contains "has operating-mode" ctx "<operating-mode>")
    (assert-contains "has challenge content" ctx "#challenge"))
  (assert-match-sh "composite+extra" {:prompt "do it #code #challenge" :cwd cwd}))

(defn test-continuation []
  (println "\n== Continuation (no hashtags, read state) ==")
  (let [sid (fresh-session)
        ;; First: activate behaviors
        _ (run-bb {:prompt "fix it #=debug #deep" :session_id sid :cwd cwd})
        ;; Then: prompt without hashtags
        result (run-bb {:prompt "what about the auth middleware?" :session_id sid :cwd cwd})
        ctx (parse-context (:stdout result))]
    (assert-eq "exit 0" 0 (:exit result))
    (assert-contains "has Active:" ctx "Active: #=debug #deep")
    (assert-contains "has HARD CONSTRAINT" ctx "HARD CONSTRAINT")))

(defn test-continuation-matches-sh []
  (println "\n== Continuation bb=sh ==")
  ;; Activate with bb, continue with bb; activate with sh, continue with sh; compare continuation output
  (let [bb-sid (fresh-session)
        sh-sid (fresh-session)
        input {:prompt "#=review #deep" :cwd cwd}
        _ (run-bb (assoc input :session_id bb-sid))
        _ (run-sh (assoc input :session_id sh-sid))
        cont-input {:prompt "next step" :cwd cwd}
        bb-cont (run-bb (assoc cont-input :session_id bb-sid))
        sh-cont (run-sh (assoc cont-input :session_id sh-sid))
        bb-ctx (parse-context (:stdout bb-cont))
        sh-ctx (parse-context (:stdout sh-cont))]
    (if (= bb-ctx sh-ctx)
      (pass! "continuation bb=sh")
      (fail! "continuation bb=sh" "continuation outputs differ"))))

(defn test-state-file-format []
  (println "\n== State file format ==")
  (let [sid (fresh-session)
        _ (run-bb {:prompt "plan it #=spec #deep" :session_id sid :cwd cwd})
        state (read-state-file sid)]
    (assert-eq "state has original tags" "#=spec #deep" state))
  ;; Composite: state stores composite name, not expanded
  (let [sid (fresh-session)
        _ (run-bb {:prompt "do it #code" :session_id sid :cwd cwd})
        state (read-state-file sid)]
    (assert-eq "state stores composite name" "#code" state)))

(defn test-state-filters-unknown []
  (println "\n== State filters unknown behaviors ==")
  (let [sid (fresh-session)
        _ (run-bb {:prompt "#=code #nonexistent #deep" :session_id sid :cwd cwd})
        state (read-state-file sid)]
    (assert-eq "unknown filtered from state" "#=code #deep" state)))

(defn test-clear []
  (println "\n== #CLEAR ==")
  (let [sid (fresh-session)
        ;; Activate
        _ (run-bb {:prompt "#=code #deep" :session_id sid :cwd cwd})
        _ (assert-true "state exists before clear" (not (str/blank? (read-state-file sid))))
        ;; Clear
        result (run-bb {:prompt "#CLEAR" :session_id sid :cwd cwd})]
    (assert-eq "exit 0" 0 (:exit result))
    (assert-true "no stdout" (str/blank? (:stdout result)))
    (assert-true "state cleared" (str/blank? (read-state-file sid)))))

(defn test-clear-conflict []
  (println "\n== #CLEAR conflict ==")
  (let [sid (fresh-session)
        result (run-bb {:prompt "#CLEAR #deep" :session_id sid :cwd cwd})]
    (assert-eq "exit 2" 2 (:exit result))
    (assert-contains "stderr has conflict msg" (:stderr result) "Conflict: #CLEAR cannot be combined")))

(defn test-multiple-modes []
  (println "\n== Multiple modes conflict ==")
  (let [sid (fresh-session)
        result (run-bb {:prompt "#=code #=debug" :session_id sid :cwd cwd})]
    (assert-eq "exit 2" 2 (:exit result))
    (assert-contains "stderr has conflict msg" (:stderr result) "multiple operating modes")))

(defn test-unknown-behavior []
  (println "\n== Unknown behavior ==")
  (let [sid (fresh-session)
        result (run-bb {:prompt "#=code #nonexistent" :session_id sid :cwd cwd})
        ctx (parse-context (:stdout result))]
    (assert-eq "exit 0" 0 (:exit result))
    (assert-contains "stderr warns" (:stderr result) "Unknown behaviors")
    (assert-contains "still produces output" ctx "<operating-mode>")))

(defn test-explain-with-tags []
  (println "\n== #EXPLAIN with tags ==")
  (let [sid (fresh-session)
        result (run-bb {:prompt "#EXPLAIN #=code #deep" :session_id sid :cwd cwd})
        ctx (parse-context (:stdout result))]
    (assert-eq "exit 0" 0 (:exit result))
    (assert-contains "has explain-instruction" ctx "<explain-instruction>")
    (assert-contains "has explain-behaviors" ctx "<explain-behaviors>")
    (assert-contains "has mode behavior" ctx "role=\"mode\"")
    (assert-contains "has modifier behavior" ctx "role=\"modifier\""))
  (assert-match-sh "#EXPLAIN with tags" {:prompt "#EXPLAIN #=code #deep" :cwd cwd}))

(defn test-explain-composite []
  (println "\n== #EXPLAIN with composite ==")
  (let [sid (fresh-session)
        result (run-bb {:prompt "#EXPLAIN #code" :session_id sid :cwd cwd})
        ctx (parse-context (:stdout result))]
    (assert-eq "exit 0" 0 (:exit result))
    (assert-contains "has expansion-tree" ctx "<expansion-tree>")
    (assert-contains "has tree connectors" ctx "├── ")
    (assert-contains "has explain-behaviors" ctx "<explain-behaviors>"))
  (assert-match-sh "#EXPLAIN composite" {:prompt "#EXPLAIN #code" :cwd cwd}))

(defn test-explain-from-state []
  (println "\n== #EXPLAIN from state ==")
  (let [sid (fresh-session)
        ;; Activate first
        _ (run-bb {:prompt "#=review #deep" :session_id sid :cwd cwd})
        ;; Then EXPLAIN without companions
        result (run-bb {:prompt "#EXPLAIN" :session_id sid :cwd cwd})
        ctx (parse-context (:stdout result))]
    (assert-eq "exit 0" 0 (:exit result))
    (assert-contains "has explain-instruction" ctx "<explain-instruction>")
    (assert-contains "has #=review content" ctx "#=review")))

(defn test-explain-no-state []
  (println "\n== #EXPLAIN no state (error) ==")
  (let [sid (fresh-session)
        result (run-bb {:prompt "#EXPLAIN" :session_id sid :cwd cwd})]
    (assert-eq "exit 2" 2 (:exit result))
    (assert-contains "stderr has error" (:stderr result) "No active behaviors to explain")))

(defn test-explain-multiple-modes []
  (println "\n== #EXPLAIN multiple modes (error) ==")
  (let [sid (fresh-session)
        result (run-bb {:prompt "#EXPLAIN #=code #=debug" :session_id sid :cwd cwd})]
    (assert-eq "exit 2" 2 (:exit result))
    (assert-contains "stderr has conflict" (:stderr result) "multiple operating modes")))

(defn test-hashtag-extraction []
  (println "\n== Hashtag extraction edge cases ==")
  ;; Hashtags on the last line with mode anchor
  (let [sid (fresh-session)
        result (run-bb {:prompt "line one\nline two #=code #deep" :session_id sid :cwd cwd})
        ctx (parse-context (:stdout result))]
    (assert-eq "exit 0" 0 (:exit result))
    (assert-contains "picks last line with #=" ctx "<operating-mode>"))

  ;; Mode on first line, modifiers on second — should pick mode line
  (let [sid (fresh-session)
        result (run-bb {:prompt "#=code\n#deep #challenge" :session_id sid :cwd cwd})
        ctx (parse-context (:stdout result))]
    (assert-eq "exit 0 (mode line wins)" 0 (:exit result))
    ;; The #= line anchors, so only #=code is extracted (not #deep #challenge)
    (assert-contains "has operating-mode" ctx "<operating-mode>")
    (assert-not-contains "deep not extracted (not on mode line)" ctx "#deep")))

(defn test-dedup-in-expansion []
  (println "\n== Leaf dedup in expansion ==")
  ;; #code contains #g which contains #deep. Adding #deep again shouldn't double it.
  (let [sid (fresh-session)
        result (run-bb {:prompt "#code #deep" :session_id sid :cwd cwd})
        ctx (parse-context (:stdout result))]
    (assert-eq "exit 0" 0 (:exit result))
    ;; Count occurrences of the #deep header in modifiers section
    (let [deep-count (count (re-seq #"# #deep" ctx))]
      (assert-eq "deep appears once" 1 deep-count))))

(defn test-no-session-id []
  (println "\n== No session ID ==")
  (let [result (run-bb {:prompt "#=code" :cwd cwd})]
    (assert-eq "exit 0" 0 (:exit result))
    (assert-contains "still produces output" (parse-context (:stdout result)) "<operating-mode>")))

(defn test-missing-cwd []
  (println "\n== Missing/empty cwd ==")
  (let [sid (fresh-session)
        result (run-bb {:prompt "#=code" :session_id sid :cwd ""})]
    (assert-eq "exit 0" 0 (:exit result))
    (assert-contains "still produces output" (parse-context (:stdout result)) "<operating-mode>")))

(defn test-mode-ordering-in-state []
  (println "\n== State: modes before modifiers ==")
  (let [sid (fresh-session)
        ;; Input order: modifier first, then mode
        _ (run-bb {:prompt "#deep #=code" :session_id sid :cwd cwd})
        state (read-state-file sid)]
    ;; State should have modes first
    (assert-eq "mode first in state" "#=code #deep" state)))

;; --- Run all tests ---

(defn -main []
  (println (str "Running e2e tests against: " bb-script))
  (println (str "Reference shell script: " sh-script))
  (println (str "Repo dir: " repo-dir))
  (println "")

  (test-empty-prompt)
  (test-no-hashtags-no-state)
  (test-mode-only)
  (test-mode-plus-modifier)
  (test-modifiers-only)
  (test-composite-expansion)
  (test-composite-plus-extra)
  (test-continuation)
  (test-continuation-matches-sh)
  (test-state-file-format)
  (test-state-filters-unknown)
  (test-clear)
  (test-clear-conflict)
  (test-multiple-modes)
  (test-unknown-behavior)
  (test-explain-with-tags)
  (test-explain-composite)
  (test-explain-from-state)
  (test-explain-no-state)
  (test-explain-multiple-modes)
  (test-hashtag-extraction)
  (test-dedup-in-expansion)
  (test-no-session-id)
  (test-missing-cwd)
  (test-mode-ordering-in-state)

  (println "")
  (println (str "========================================"))
  (println (str "  " @*pass-count* " passed, " @*fail-count* " failed"))
  (println (str "========================================"))

  (when (seq @*failures*)
    (println "\nFailures:")
    (doseq [{:keys [name reason]} @*failures*]
      (println (str "  - " name))
      (println (str "    " reason)))
    (println ""))

  (System/exit (if (zero? @*fail-count*) 0 1)))

(-main)
