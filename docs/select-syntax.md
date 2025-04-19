### Select DSL

It's a common practice to deal with highly nested or complex data structures when we're working with third-party APIs
and unstructured data. To make things easier, Collet offers a small DSL. You can describe what you want to select from
the data structure in a declarative way.

In the basic form you can think of that as a "path vector", similar to what you'll use in the Clojure `get-in` function.
Elements in the "path vector" could be a keywords, integers or strings.

```clojure
{:return [:state :user :name]}
```

In example above, we're selecting the `:name` key from the `:user` key in the `:state` map.
In addition, select DSL supports some additional functions to make it more powerful:

- You can use a "map syntax" to select multiple keys at the same time. If element in the "path vector" is a map, it will
  be treated as a map of keys to select. Map keys will be included in the resulting map and keys can be a "path vectors"
  itself.

```clojure
;; let's say your state looks like this:
{:state {:user {:name      "John"
                :last-name "Doe"
                :phone     "123-456-7890"
                :age       25
                :address   {:street "123 Main St."
                            :city   "Springfield"}}}}

;; you can specify in your task iterator key:
{:return [:state :user {:user-name :name
                        :street    [:address :street]}]}

;; and the result will be:
{:user-name "John"
 :street    "123 Main St."}
```

- Another special syntax for selecting values is a `:$/cat` function. You can use it to iterate over a collection.
  Functions in select DSL are defined as a vector with specific key in the first position. Syntax for `:$/cat` is
  `[:$/cat path-inside-each-element]`.

```clojure
;; let's say your state looks like this:
{:state {:users [{:first-name "John" :last-name "Doe" :age 25}
                 {:first-name "Jane" :last-name "Doe" :age 30}
                 {:first-name "Alice" :last-name "Smith" :age 35}
                 {:first-name "Bob" :last-name "Smith" :age 40}]}}

;; using the :$/cat function. 
;; notice that :first-name is a key in each user map, but you can use more complex paths as well
{:return [:state :users [:$/cat :first-name]]}

;; will return:
["John" "Jane" "Alice" "Bob"]
```

- Next function is `:$/cond`. It allows you to filter the results based on a condition. The syntax is
  `[:$/cond condition]`. The condition is a vector with the first element being a keyword representing the operator and
  the rest of the elements being the arguments for the operator.

```clojure
;; let's say your state looks like this:
{:state {:users [{:first-name "John" :last-name "Doe" :age 15}
                 {:first-name "Jane" :last-name "Doe" :age 30}
                 {:first-name "Alice" :last-name "Smith" :age 17}
                 {:first-name "Bob" :last-name "Smith" :age 40}]}}

;; notice how you can combine :$/cat and :$/cond functions
{:return [:state :users [:$/cat :first-name [:$/cond [:> :age 18]]]]}

;; will return:
["Jane" "Bob"]
```

- One more function available is `:$/op`. It allows you to perform a specific operation on the selected data. For only
  two operations are supported: `:first` and `:last`. This list will be extended in the future.

```clojure
;; let's say your state looks like this:
{:state {:users [{:first-name "John" :last-name "Doe" :age 15}
                 {:first-name "Jane" :last-name "Doe" :age 30}
                 {:first-name "Alice" :last-name "Smith" :age 17}
                 {:first-name "Bob" :last-name "Smith" :age 40}]}}

{:return [:state :users [:$/op :first] :first-name]}

;; will return:
"John"
```

Of course, you can combine all of these functions together to create more complex queries. For example, you can use
the following sample to get the last name of the last user who is older than 18.
The result will be `"Smith"`.

```clojure
;; let's say your state looks like this:
{:state {:users [{:first-name "John" :last-name "Doe" :age 15}
                 {:first-name "Jane" :last-name "Doe" :age 30}
                 {:first-name "Alice" :last-name "Smith" :age 17}
                 {:first-name "Bob" :last-name "Smith" :age 40}]}}

{:return [:state :users [:$/cat [:$/cond [:> :age 18]] :last-name] [:$/op :last]]}

;; will return:
;; "Smith"
```