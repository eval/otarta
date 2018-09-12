(ns otarta.util)


(defmacro ^:private <err-*
  [threading init & exprs]
  (let [placeholder (gensym)]
    `(as-> ~init ~placeholder ~@(for [expr exprs]
                                  `(let [result#  (cljs.core.async/<! ~placeholder)]
                                     (if (not (first result#))
                                       (~threading result# second ~expr)
                                       (cljs.core.async.macros/go result#)))))))


(defmacro <err->
  "Each of the forms should yield a tuple [err result] wrapped
  in an async-channel. As long as err is nil, result is passed onto
  the next form as the first argument. As soon as an err is not
  nil (or there are no more forms), the tuple is returned.

  Example:
  (go
    (let [[err result] (<! (<err-> {:url \"some-url\"}
                                   (connect {:keep-alive 10})
                                   (do-query {:some :selector})))]
      (if err
        (println \"Something went wrong:\" err)
        (println \"Query result:\" result))))
"
  [x & forms]
  `(cljs.core.async.macros/go (cljs.core.async/<!
                               (<err-* -> (cljs.core.async.macros/go [nil ~x]) ~@forms))))


(defmacro <err->>
  "Each of the forms should yield a tuple [err result] wrapped
  in an async-channel. As long as err is nil, result is passed onto
  the next form as the last argument. As soon as err is not
  nil (or there are no more forms), the tuple is returned.

  Example:
  (go
    (let [[err result] (<! (<err->> connect-opts
                                    (connect client)))]
      (if err
        (println \"Something went wrong:\" err)
        (println \"Connect result:\" result))))
"
  [x & forms]
  `(cljs.core.async.macros/go (cljs.core.async/<!
                               (<err-* ->> (cljs.core.async.macros/go [nil ~x]) ~@forms))))
