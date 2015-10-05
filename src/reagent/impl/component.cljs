(ns reagent.impl.component
  (:require [reagent.impl.util :as util]
            [reagent.impl.batching :as batch]
            [reagent.ratom :as ratom]
            [reagent.interop :refer-macros [.' .!]]
            [reagent.debug :refer-macros [dbg prn dev? warn]]))

(declare ^:dynamic *current-component*)

(declare ^:dynamic *non-reactive*)

;;; State

(defn state-atom [this]
  (let [sa (.' this :cljsState)]
    (if-not (nil? sa)
      sa
      (.! this :cljsState (ratom/atom nil)))))

;; ugly circular dependency
(defn as-element [x]
  (js/reagent.impl.template.as-element x))

;;; Rendering

(defn reagent-class? [c]
  (and (fn? c)
       (some? (.' c :cljsReactClass))))

(defn do-render-sub [c]
  (let [f (.' c :reagentRender)
        _ (assert (ifn? f))
        p (.' c :props)
        res (if (true? (.' c :cljsLegacyRender))
              (f c)
              (let [argv (.' p :argv)
                    n (count argv)]
                (case n
                  1 (f)
                  2 (f (nth argv 1))
                  3 (f (nth argv 1) (nth argv 2))
                  4 (f (nth argv 1) (nth argv 2) (nth argv 3))
                  5 (f (nth argv 1) (nth argv 2) (nth argv 3) (nth argv 4))
                  (apply f (subvec argv 1)))))]
    (if (vector? res)
      (as-element res)
      (if (ifn? res)
        (let [f (if (reagent-class? res)
                  (fn [& args]
                    (as-element (apply vector res args)))
                  res)]
          (.! c :reagentRender f)
          (recur c))
        res))))

(declare comp-name)

(defn do-render [c]
  (binding [*current-component* c]
    (if (dev?)
      ;; Log errors, without using try/catch (and mess up call stack)
      (let [ok (array false)]
        (try
          (let [res (do-render-sub c)]
            (aset ok 0 true)
            res)
          (finally
            (when-not (aget ok 0)
              (js/console.error (str "Error rendering component "
                                     (comp-name)))))))
      (do-render-sub c))))


;;; Method wrapping

(def rat-opts {:no-cache true})

(def static-fns
  {:render
   (fn render []
     (this-as c (if *non-reactive*
                  (do-render c)
                  (let [rat (.' c :cljsRatom)]
                    (batch/mark-rendered c)
                    (if (nil? rat)
                      (ratom/run-in-reaction #(do-render c) c "cljsRatom"
                                             batch/queue-render rat-opts)
                      (._run rat))))))})

(defn custom-wrapper [key f]
  (case key
    :getDefaultProps
    (assert false "getDefaultProps not supported yet")

    :getInitialState
    (fn []
      (this-as c
               (reset! (state-atom c) (f c))))

    :componentWillReceiveProps
    (fn [props]
      (this-as c
               (f c (.' props :argv))))

    :shouldComponentUpdate
    (fn [nextprops nextstate]
      (or util/*always-update*
          (this-as c
                   ;; Don't care about nextstate here, we use forceUpdate
                   ;; when only when state has changed anyway.
                   (let [old-argv (.' c :props.argv)
                         new-argv (.' nextprops :argv)]
                     (if (nil? f)
                       (or (nil? old-argv)
                           (nil? new-argv)
                           (not= old-argv new-argv))
                       (f c old-argv new-argv))))))

    :componentWillUpdate
    (fn [nextprops]
      (this-as c
               (f c (.' nextprops :argv))))

    :componentDidUpdate
    (fn [oldprops]
      (this-as c
               (f c (.' oldprops :argv))))

    :componentWillMount
    (fn []
      (this-as c
               (.! c :cljsMountOrder (batch/next-mount-count))
               (when-not (nil? f)
                 (f c))))

    :componentWillUnmount
    (fn []
      (this-as c
               (some-> (.' c :cljsRatom)
                       ratom/dispose!)
               (batch/mark-rendered c)
               (when-not (nil? f)
                 (f c))))

    nil))

(defn default-wrapper [f]
  (if (ifn? f)
    (fn [& args]
      (this-as c (apply f c args)))
    f))

(def dont-wrap #{:render :reagentRender :cljsName})

(defn dont-bind [f]
  (if (fn? f)
    (doto f
      (.! :__reactDontBind true))
    f))

(defn get-wrapper [key f name]
  (if (dont-wrap key)
    (dont-bind f)
    (let [wrap (custom-wrapper key f)]
      (when (and wrap f)
        (assert (ifn? f)
                (str "Expected function in " name key " but got " f)))
      (or wrap (default-wrapper f)))))

(def obligatory {:shouldComponentUpdate nil
                 :componentWillMount nil
                 :componentWillUnmount nil})

(def dash-to-camel (util/memoize-1 util/dash-to-camel))

(defn camelify-map-keys [fun-map]
  (reduce-kv (fn [m k v]
               (assoc m (-> k dash-to-camel keyword) v))
             {} fun-map))

(defn add-obligatory [fun-map]
  (merge obligatory fun-map))

(defn add-render [fun-map render-f name]
  (let [fm (assoc fun-map
                  :reagentRender render-f
                  :render (:render static-fns))]
    (if (dev?)
      (assoc fm :cljsName (fn [] name))
      fm)))

(defn fun-name [f]
  (or (and (fn? f)
           (or (.' f :displayName)
               (.' f :name)))
      (and (implements? INamed f)
           (name f))
      (let [m (meta f)]
        (if (map? m)
          (:name m)))))

(defn wrap-funs [fmap]
  (let [fun-map (if-some [cf (:componentFunction fmap)]
                  (-> fmap
                      (assoc :reagentRender cf)
                      (dissoc :componentFunction))
                  fmap)
        render-fun (:reagentRender fun-map)
        legacy-render (nil? render-fun)
        render-fun (or render-fun
                       (:render fun-map))
        _ (assert (ifn? render-fun)
                  (str "Render must be a function, not "
                       (pr-str render-fun)))
        name (str (or (:displayName fun-map)
                      (fun-name render-fun)))
        name (if (empty? name)
               (str (gensym "reagent"))
               (clojure.string/replace name #"\$" "."))
        fmap (-> fun-map
                 (assoc :cljsLegacyRender legacy-render
                        :displayName name)
                 (add-render render-fun name))]
    (reduce-kv (fn [m k v]
                 (assoc m k (get-wrapper k v name)))
               {} fmap)))

(defn map-to-js [m]
  (reduce-kv (fn [o k v]
               (doto o
                 (aset (name k) v)))
             #js{} m))

(defn cljsify [body]
  (-> body
      camelify-map-keys
      add-obligatory
      wrap-funs
      map-to-js))

(defn create-class
  [body]
  (assert (map? body))
  (let [spec (cljsify body)
        res (.' js/React createClass spec)]
    (util/cache-react-class res res)
    res))

(defn component-path [c]
  (let [elem (some-> (or (some-> c
                                 (.' :_reactInternalInstance))
                          c)
                     (.' :_currentElement))
        name (some-> elem
                     (.' :type)
                     (.' :displayName))
        path (some-> elem
                     (.' :_owner)
                     component-path
                     (str " > "))
        res (str path name)]
    (when-not (empty? res) res)))

(defn comp-name []
  (if (dev?)
    (let [c *current-component*
          n (or (component-path c)
                (some-> c (.' cljsName)))]
      (if-not (empty? n)
        (str " (in " n ")")
        ""))
    ""))

(defn shallow-obj-to-map [o]
  (into {} (for [k (js-keys o)]
             [(keyword k) (aget o k)])))

(def elem-counter 0)

(defn reactify-component [comp]
  (.' js/React createClass
      #js{:displayName "react-wrapper"
          :render
          (fn []
            (this-as this
                     (as-element
                      [comp
                       (-> (.' this :props)
                           shallow-obj-to-map
                           ;; ensure re-render, might get mutable js data
                           (assoc :-elem-count
                                  (set! elem-counter
                                        (inc elem-counter))))])))}))
