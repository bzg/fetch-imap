(ns build
  (:require [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

(def lib 'org.clojars.bzg/fetch-imap)
(def version "0.2.2")
(def class-dir "target/classes")
(def jar-file (format "target/%s-%s.jar" (name lib) version))
(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [_]
  (clean nil)
  (b/write-pom {:class-dir class-dir
                :lib       lib
                :version   version
                :basis     @basis
                :src-dirs  ["src"]
                :pom-data  [[:description "Minimal, read-only Clojure library for fetching and parsing IMAP email"]
                            [:url "https://github.com/bzg/fetch-imap"]
                            [:licenses
                             [:license
                              [:name "Eclipse Public License 2.0"]
                              [:url "https://www.eclipse.org/legal/epl-2.0/"]]]]
                :scm       {:url "https://github.com/bzg/fetch-imap"
                            :connection "scm:git:git://github.com/bzg/fetch-imap.git"
                            :developerConnection "scm:git:ssh://git@github.com/bzg/fetch-imap.git"
                            :tag (str "v" version)}})
  (b/copy-dir {:src-dirs   ["src"]
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file  jar-file}))

(defn deploy [_]
  (jar nil)
  (dd/deploy {:installer :remote
              :artifact  (b/resolve-path jar-file)
              :pom-file  (b/pom-path {:lib lib :class-dir class-dir})}))

(defn install [_]
  (jar nil)
  (dd/deploy {:installer :local
              :artifact  (b/resolve-path jar-file)
              :pom-file  (b/pom-path {:lib lib :class-dir class-dir})}))
