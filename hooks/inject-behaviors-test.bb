#!/usr/bin/env bb

(ns inject-behaviors-test
  (:require [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; --- Test infrastructure ---

(def repo-dir (.getCanonicalPath (io/file (str (.getParent (io/file *file*)) "/.."))))
(def bb-script (str repo-dir "/hooks/inject-behaviors.bb"))
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

;; --- Test groups ---

(defn test-empty-prompt []
  (println "\n== Empty prompt ==")
  (let [sid (fresh-session)
        result (run-bb {:prompt "" :session_id sid :cwd cwd})]
    (assert-eq "exit 0" 0 (:exit result))
    (assert-true "no stdout" (str/blank? (:stdout result)))))

(defn test-no-hashtags-no-state []
  (println "\n== No hashtags, no state → default frame ==")
  (let [sid (fresh-session)
        result (run-bb {:prompt "just a regular prompt" :session_id sid :cwd cwd})
        ctx (parse-context (:stdout result))]
    (assert-eq "exit 0" 0 (:exit result))
    (assert-contains "default injects frame mode" ctx "<operating-mode>")
    (assert-contains "default has frame content" ctx "frame :: Context")
    (assert-contains "default has #g modifiers" ctx "#deep")
    (assert-contains "default has #ground modifier" ctx "Verify every term resolves")
    ;; State should be written so continuation works
    (let [state (read-state-file sid)]
      (assert-contains "default writes state" state "#=frame"))
))

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
    (assert-contains "has compaction instruction" ctx "During compaction")))

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
    (assert-contains "has ask-tool instruction" ctx "AskUserQuestion")))

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
    (assert-not-contains "no ask-tool (no mode)" ctx "AskUserQuestion")))

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
    (assert-contains "has #deep content (from #g)" ctx "#deep")))

(defn test-composite-plus-extra []
  (println "\n== Composite + extra modifier ==")
  (let [sid (fresh-session)
        result (run-bb {:prompt "do it #code #challenge" :session_id sid :cwd cwd})
        ctx (parse-context (:stdout result))]
    (assert-eq "exit 0" 0 (:exit result))
    (assert-contains "has operating-mode" ctx "<operating-mode>")
    (assert-contains "has challenge content" ctx "#challenge")))

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
    (assert-contains "has modifier behavior" ctx "role=\"modifier\"")))

(defn test-explain-composite []
  (println "\n== #EXPLAIN with composite ==")
  (let [sid (fresh-session)
        result (run-bb {:prompt "#EXPLAIN #code" :session_id sid :cwd cwd})
        ctx (parse-context (:stdout result))]
    (assert-eq "exit 0" 0 (:exit result))
    (assert-contains "has expansion-tree" ctx "<expansion-tree>")
    (assert-contains "has tree connectors" ctx "├── ")
    (assert-contains "has explain-behaviors" ctx "<explain-behaviors>")))

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

(defn test-default-after-clear []
  (println "\n== Default frame after #CLEAR ==")
  (let [sid (fresh-session)
        ;; Activate, then clear
        _ (run-bb {:prompt "#=code" :session_id sid :cwd cwd})
        _ (run-bb {:prompt "#CLEAR" :session_id sid :cwd cwd})
        ;; Next bare prompt should get default frame
        result (run-bb {:prompt "what now?" :session_id sid :cwd cwd})
        ctx (parse-context (:stdout result))]
    (assert-eq "exit 0" 0 (:exit result))
    (assert-contains "post-clear default injects frame" ctx "frame :: Context")
    (assert-contains "post-clear default has modifiers" ctx "<behavior-modifiers>")))

(defn test-default-continuation []
  (println "\n== Default frame continuation ==")
  (let [sid (fresh-session)
        ;; First prompt: no hashtags, no state → default frame activates and writes state
        _ (run-bb {:prompt "hello" :session_id sid :cwd cwd})
        ;; Second prompt: no hashtags, state exists → continuation
        result (run-bb {:prompt "follow up" :session_id sid :cwd cwd})
        ctx (parse-context (:stdout result))]
    (assert-eq "exit 0" 0 (:exit result))
    (assert-contains "continuation shows Active" ctx "Active:")
    (assert-contains "continuation has frame" ctx "#=frame")))

(defn test-default-override []
  (println "\n== Explicit hashtag overrides default ==")
  (let [sid (fresh-session)
        ;; First prompt: no hashtags → default frame
        _ (run-bb {:prompt "hello" :session_id sid :cwd cwd})
        ;; Second prompt: explicit #=code overrides
        result (run-bb {:prompt "now code #=code" :session_id sid :cwd cwd})
        ctx (parse-context (:stdout result))]
    (assert-eq "exit 0" 0 (:exit result))
    (assert-contains "override has code mode" ctx "Write production code")
    (assert-not-contains "no frame content" ctx "frame :: Context")
    (let [state (read-state-file sid)]
      (assert-contains "state updated to code" state "#=code"))))

(defn test-mode-ordering-in-state []
  (println "\n== State: modes before modifiers ==")
  (let [sid (fresh-session)
        ;; Input order: modifier first, then mode
        _ (run-bb {:prompt "#deep #=code" :session_id sid :cwd cwd})
        state (read-state-file sid)]
    ;; State should have modes first
    (assert-eq "mode first in state" "#=code #deep" state)))

;; --- Fix A: XML block stripping ---

(defn test-xml-blocks-stripped-from-extraction []
  (println "\n== Fix A: XML blocks stripped before hashtag extraction ==")
  ;; #uuid inside <knowledge-context> should NOT be extracted as a hashtag
  (let [sid (fresh-session)
        prompt (str "<knowledge-context>\nSome doc with #uuid references and #api-key mentions\n</knowledge-context>\njust a regular prompt")
        result (run-bb {:prompt prompt :session_id sid :cwd cwd})
        ctx (parse-context (:stdout result))]
    (assert-eq "exit 0" 0 (:exit result))
    ;; Should fall through to default frame (no real hashtags found)
    (assert-contains "default frame injected" ctx "frame :: Context")
    (assert-true "no unknown warnings on stderr" (not (str/includes? (or (:stderr result) "") "Unknown"))))

  ;; Real hashtag outside XML should still work
  (let [sid (fresh-session)
        prompt (str "<system-reminder>\nignore #review stuff\n</system-reminder>\nfix the bug #=debug #deep")
        result (run-bb {:prompt prompt :session_id sid :cwd cwd})
        ctx (parse-context (:stdout result))]
    (assert-eq "exit 0 (real tags work)" 0 (:exit result))
    (assert-contains "has debug mode" ctx "#=debug")
    (assert-contains "has deep modifier" ctx "#deep"))

  ;; Multiple XML blocks stripped
  (let [sid (fresh-session)
        prompt (str "<knowledge-context>\n#foo\n</knowledge-context>\n<system-reminder>\n#bar\n</system-reminder>\nhello world")
        result (run-bb {:prompt prompt :session_id sid :cwd cwd})
        ctx (parse-context (:stdout result))]
    (assert-eq "exit 0 (multi XML)" 0 (:exit result))
    (assert-contains "default frame (no real tags)" ctx "frame :: Context"))

  ;; Nested-looking content (non-matching tags) still stripped correctly
  (let [sid (fresh-session)
        prompt (str "<user_injected_message>\nsome #inject stuff\n</user_injected_message>\ndo thing #=code")
        result (run-bb {:prompt prompt :session_id sid :cwd cwd})
        ctx (parse-context (:stdout result))]
    (assert-eq "exit 0 (underscore tags)" 0 (:exit result))
    (assert-contains "has code mode" ctx "#=code")
    (assert-not-contains "no inject artifact" ctx "#inject")))

;; --- Fix B: Empty state truthy ---

(defn test-empty-state-returns-nil []
  (println "\n== Fix B: Empty/newline state file falls through to default ==")
  ;; Simulate the original bug: 1-byte state file (just newline)
  (let [sid (fresh-session)
        state-f (io/file state-dir sid)]
    (.mkdirs (io/file state-dir))
    (spit state-f "\n")
    ;; Bare prompt should fall through to default, not enter continuation
    (let [result (run-bb {:prompt "hello" :session_id sid :cwd cwd})
          ctx (parse-context (:stdout result))]
      (assert-eq "exit 0" 0 (:exit result))
      (assert-contains "default frame (not continuation)" ctx "frame :: Context")
      (assert-not-contains "no Active: prefix (not continuation)" ctx "Active:")))

  ;; Empty file (0 bytes) should also fall through
  (let [sid (fresh-session)
        state-f (io/file state-dir sid)]
    (spit state-f "")
    (let [result (run-bb {:prompt "hello" :session_id sid :cwd cwd})
          ctx (parse-context (:stdout result))]
      (assert-eq "exit 0 (0-byte)" 0 (:exit result))
      (assert-contains "default frame (0-byte)" ctx "frame :: Context")))

  ;; Whitespace-only state file
  (let [sid (fresh-session)
        state-f (io/file state-dir sid)]
    (spit state-f "   \n  \n")
    (let [result (run-bb {:prompt "hello" :session_id sid :cwd cwd})
          ctx (parse-context (:stdout result))]
      (assert-eq "exit 0 (whitespace)" 0 (:exit result))
      (assert-contains "default frame (whitespace-only)" ctx "frame :: Context"))))

(defn test-write-state-noop-on-blank []
  (println "\n== Fix B: write-state! no-ops on blank content ==")
  ;; All-unknown activation should NOT create/overwrite state file
  (let [sid (fresh-session)
        ;; First: activate real behaviors
        _ (run-bb {:prompt "#=code #deep" :session_id sid :cwd cwd})
        state-before (read-state-file sid)]
    (assert-eq "state has real tags" "#=code #deep" state-before)
    ;; Now: another activation where partial tags resolve
    ;; (This tests that write-state! works normally when content is non-blank)
    (run-bb {:prompt "#=debug" :session_id sid :cwd cwd})
    (let [state-after (read-state-file sid)]
      (assert-eq "state updated to debug" "#=debug" state-after))))

;; --- Fix C: All-unknown hashtags ---

(defn test-all-unknown-falls-through []
  (println "\n== Fix C: All-unknown hashtags → treat as no hashtags ==")
  ;; Single unknown tag, no existing state → default frame
  (let [sid (fresh-session)
        result (run-bb {:prompt "do something #nonexistent" :session_id sid :cwd cwd})
        ctx (parse-context (:stdout result))]
    (assert-eq "exit 0" 0 (:exit result))
    (assert-contains "stderr warns about unknown" (:stderr result) "Unknown behaviors")
    (assert-contains "default frame activated" ctx "frame :: Context"))

  ;; Multiple unknown tags → default frame
  (let [sid (fresh-session)
        result (run-bb {:prompt "#fake1 #fake2 #fake3" :session_id sid :cwd cwd})
        ctx (parse-context (:stdout result))]
    (assert-eq "exit 0 (multi unknown)" 0 (:exit result))
    (assert-contains "stderr warns" (:stderr result) "Unknown behaviors")
    (assert-contains "default frame (multi)" ctx "frame :: Context"))

  ;; All unknown with existing state → continuation (not default)
  (let [sid (fresh-session)
        _ (run-bb {:prompt "#=debug #deep" :session_id sid :cwd cwd})
        result (run-bb {:prompt "#totallyfake" :session_id sid :cwd cwd})
        ctx (parse-context (:stdout result))]
    (assert-eq "exit 0 (unknown + state)" 0 (:exit result))
    (assert-contains "continuation path" ctx "Active: #=debug #deep"))

  ;; Mixed known + unknown: normal activation (NOT all-unknown path)
  (let [sid (fresh-session)
        result (run-bb {:prompt "#=code #nonexistent" :session_id sid :cwd cwd})
        ctx (parse-context (:stdout result))]
    (assert-eq "exit 0 (mixed)" 0 (:exit result))
    (assert-contains "code mode activated" ctx "#=code")
    (assert-contains "warns about unknown" (:stderr result) "Unknown behaviors")
    (let [state (read-state-file sid)]
      (assert-eq "state has only resolved" "#=code" state))))

(defn test-all-unknown-no-state-poisoning []
  (println "\n== Fix C: All-unknown doesn't poison state ==")
  (let [sid (fresh-session)
        ;; Activate real behaviors
        _ (run-bb {:prompt "#=code #deep" :session_id sid :cwd cwd})
        state-before (read-state-file sid)
        ;; All unknown — should NOT overwrite state
        _ (run-bb {:prompt "#bogus" :session_id sid :cwd cwd})
        state-after (read-state-file sid)]
    (assert-eq "state preserved" state-before state-after)))

;; --- Run all tests ---

(defn -main []
  (println (str "Running e2e tests against: " bb-script))
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
  (test-default-after-clear)
  (test-default-continuation)
  (test-default-override)
  (test-mode-ordering-in-state)

  ;; Fix A: XML block stripping
  (test-xml-blocks-stripped-from-extraction)
  ;; Fix B: Empty state truthy
  (test-empty-state-returns-nil)
  (test-write-state-noop-on-blank)
  ;; Fix C: All-unknown hashtags
  (test-all-unknown-falls-through)
  (test-all-unknown-no-state-poisoning)

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
