
- You can use a map syntax to select multiple keys at the same time:

```clojure
{:data [:state :user {:name :first-name :age :user-age}]}
;; This will select this map {:first-name "John" :user-age 30} from the state
```

- `:$/cat` function to iterate over a collection

```clojure
{:data [:state :users [:$/cat :first-name]]}
;; This will select all first names from the users collection
```

- `:$/cond` function to select element that match the criteria

```clojure
{:data [:state :users [:$/cat :first-name [:cond [:> :age 18]]]]}
;; This will select all first names from the users collection where age is greater than 18
```
