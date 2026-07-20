(ns applied-science.darkstar)


(def ^:dynamic *base-directory* nil)


(defn read-file
  "A small polyfill for Node's fs.readFile that uses `*base-directory*` as Vega's current directory."
  [filename]
  (slurp (.getAbsolutePath (java.io.File. (str *base-directory* filename)))))


(def engine
  (let [engine   (.getEngineByName (javax.script.ScriptEngineManager.) "graal.js")
        bindings (.getBindings engine javax.script.ScriptContext/ENGINE_SCOPE)]
    ;; These options must be installed before the first evaluation initializes
    ;; the GraalJS context. Darkstar's Java-backed fetch/fs polyfills require
    ;; both host member access and host class lookup.
    (.put bindings "polyglot.js.allowAllAccess" true)
    (.put bindings "polyglot.js.allowHostAccess" true)
    (.put bindings "polyglot.js.allowHostClassLookup" true)
    (.put bindings "darkstarReadFile"
          (reify java.util.function.Function
            (apply [_ filename]
              (read-file filename))))
    (doto engine
      (.eval "
async function fetch(path, options) {
  var body = Java.type('clojure.core$slurp').invokeStatic(path,null);
  return {'ok' : true,
          'body' : body,
          'text' : (function() {return body;}),
          'json' : (function() {return JSON.parse(body);})};
}
function readFile(path, callback) {
  try {
    var data = darkstarReadFile.apply(path);
    callback(null, data);
  } catch (err) {
    printErr(err);
  }
}
var fs = {'readFile':readFile};
")
      (.eval (slurp (clojure.java.io/resource "vega.js")))
      (.eval (slurp (clojure.java.io/resource "vega-lite.js"))))))


(defn make-js-fn
  [js-text]
  (let [^java.util.function.Function f (.eval engine js-text)]
    (fn [& args]
      (.apply f (to-array args)))))


(def vega-lite->vega
  (make-js-fn
   "function(vlSpec) { return JSON.stringify(vegaLite.compile(JSON.parse(vlSpec)).spec);}"))


(def vega-spec->view
  (make-js-fn
   "function(spec) { return new vega.View(vega.parse(JSON.parse(spec)), {renderer:'svg'}).finalize();}"))


(def view->svg
  (make-js-fn
   "function (view) {
      var promise = Java.type('clojure.core$promise').invokeStatic();
      view.toSVG(1.0).then(function(svg) {
          Java.type('clojure.core$deliver').invokeStatic(promise,svg);
      }).catch(function(err) {
          Java.type('clojure.core$deliver').invokeStatic(promise,'<svg><text>error</text></svg>');
      });
      return promise;
    }"))


(defn vega-spec->svg
  [vega-spec-json-string]
  @(view->svg (vega-spec->view vega-spec-json-string)))


(defn vega-lite-spec->svg
  [vega-lite-spec-json-string]
  (vega-spec->svg (vega-lite->vega vega-lite-spec-json-string)))
