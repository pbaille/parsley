(ns net.cgrand.parsley.tree
  "An incremental buffer backed by a 2-3 tree.")

(defprotocol Node
  "Protocol for inner nodes and leaves of a 2-3 buffer.
   Leaves contain collections (or strings or anything sequential and finite).
   The buffer maintains a reduction over all its leaves contents. 
   The buffer is parametrized by a map of fns, see the ops method."
  (len [node] "Returns the length of the Node")
  (left-cut [node offset])
  (right-cut [node offset])
  (value [node] "The result value for this node.")
  (ops [node] "Returns a map of fns with keys:
  :unit [leaf-content] 
    turns a leaf content into a value from the result type, 
  :plus [a b] (associative fn)
    combines two values from the result type into a value of the result type,
  :chunk [leaf-content]
    breaks a leaf content into a seq of leaf contents -- it controls the computational
    granularity of the buffer.
  :left [leaf-content offset] 
    returns the part of a leaf content to the left of the offset,
  :right [leaf-content offset] 
    returns the part of a leaf content to the right of the offset,
  :cat [& leaf-contents]
    returns teh concatenation of the seq of leaf-contents, with no args MUST returns the proper
    identity element (eg \"\" for str, () or [] or nil for concat),
  :len [leaf-content]
    returns the length of leaf-content."))

(defrecord Ops [unit plus chunk left right cat len])

(defn as-ops [options]
  (if (instance? Ops options)
    options
    (into (Ops. nil nil nil nil nil nil count) options)))

(defmacro cond 
  "A variation on cond which sports let bindings:
     (cond 
       (odd? a) 1
       :let [a (quot a 2)]
       (odd? a) 2
       :else 3)" 
  [& clauses]
  (when-let [[test expr & clauses] (seq clauses)]
    (if (= :let test)
      `(let ~expr (net.cgrand.parsley.tree/cond ~@clauses))
      `(if ~test ~expr (net.cgrand.parsley.tree/cond ~@clauses)))))

(deftype InnerNode [ops val length a b c]
  Node
  (left-cut [this offset]
    (cond
      :let [la (len a)]
      (<= offset la) (conj (left-cut a offset) nil)
      :let [offset (- offset la)
            lb (len b)]
      (<= offset lb) (conj (left-cut b offset) [a])
      :let [offset (- offset lb)]
      :else (conj (left-cut c offset) [a b])))
  (right-cut [this offset]
    (cond
      :let [la (len a)]
      (< offset la) (conj (right-cut a offset) (if c [b c] [b]))
      :let [offset (- offset la)
            lb (len b)]
      (< offset lb) (conj (right-cut b offset) (when c [c]))
      :let [offset (- offset lb)]
      :else (conj (right-cut c offset) nil)))
  (len [this]
    length)
  (value [this] 
    val)
  (ops [this] 
    ops))

(defn node [ops children]
  (let [[a b c] children
        plus (:plus ops)
        val (plus (value a) (value b))
        val (if c (plus val (value c)) val)]
    (InnerNode. ops val (+ (len a) (len b) (if c (len c) 0)) a b c)))

(deftype Leaf [ops val s]
  Node
  (left-cut [this offset]
    [((:left ops) s offset)])
  (right-cut [this offset]
    [((:right ops) s offset)])
  (len [this]
    ((:len ops) s))
  (value [this] 
    val)
  (ops [this] 
    ops))

(defn leaf [ops s]
  (Leaf. ops ((:unit ops) s) s))

(defn group 
  "Groups a sequence of at least two nodes into a sequence of nodes with 2 or 3 children."
  [nodes]
  (let [ops (ops (first nodes))]
    (if (odd? (count nodes))
      (cons (node ops (take 3 nodes))
            (map #(node ops %) (partition 2 (drop 3 nodes))))
      (map #(node ops %) (partition 2 nodes)))))

(defn edit 
  "Performs an edit on the buffer. Content from offset to offset+length (excluded) is replaced
   by s." 
  [tree offset length s]
  (let [[sl & lefts] (left-cut tree offset)
        [sr & rights] (right-cut tree (+ offset length))
        ops (ops tree)
        s ((:cat ops) sl s sr)
        leaves (map #(leaf ops %) ((:chunk ops) s))]
    (loop [[l & lefts] lefts [r & rights] rights nodes leaves]
      (let [nodes (concat l nodes r)]
        (if (next nodes)
          (recur lefts rights (group nodes))
          (first nodes))))))

(defn buffer
  ([ops]
    (let [ops (as-ops ops)] 
      (leaf ops ((:cat ops)))))
  ([ops s]
    (-> (buffer ops) (edit 0 0 s))))

;;;;;;;;;;;;;;;
(comment ; demo

(def str-buffer (partial buffer {:unit identity 
                                 :plus str 
                                 :chunk #(.split ^String % "(?<=\n)")
                                 :left #(subs %1 0 %2) 
                                 :right subs 
                                 :cat str}))

(defprotocol Treeable
  (tree [treeable]))

(extend-protocol Treeable
  Leaf
  (tree [leaf] (.s leaf))
  InnerNode
  (tree [nv] (map tree (if (.c nv) [(.a nv) (.b nv) (.c nv)] [(.a nv) (.b nv)]))))

;; repl session
  => (-> "a\nb" str-buffer (edit 1 0 "c") ((juxt tree value)))
  ("ac\n" "b")
  => (-> "a\nb" str-buffer (edit 1 0 "cd") ((juxt tree value)))
  ("acd\n" "b")
  => (-> "a\nb" str-buffer (edit 1 0 "cd")  (edit 2 0 "\n\n") ((juxt tree value)))
  (("ac\n" "\n") ("d\n" "b")))