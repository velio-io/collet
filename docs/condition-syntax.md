### Condition DSL

You can define conditions using vectors of shape: `[:function-name :value-path :arguments]`.

Where `:function-name` is one of the following: `:and`, `:or`, `:pos?`, `:neg?`, `:zero?`, `:>`,
`:>=`, `:<`, `:<=`, `:=`, `:always-true`, `:true?`, `:false?`, `:contains`, `:absent`, `:regex`, `:nil?`, `:not-nil?`,
`:not=`, `:empty?`, `:not-empty?`.
These names refers to their analogous functions in the `clojure.core` namespace.

`:value-path` it's a vector to some value inside the state data. Think of it as a Clojure `get-in` function path vector.

`:arguments` are optional and should be concrete values.

Here's some examples:

```clojure
;; Check if the user's age is more than 18 and less or equal 65
[:and
 [:> [:user :age] 18]
 [:<= [:user :age] 65]]

;; Check if the user's name is nil or empty string
[:or [:nil? [:user :name]]
 [:empty? [:user :name]]]

;; Check if the user's role is admin
[:contains [:user :roles] "admin"]

;; Check if the user's email is a gmail account
[:regex [:user :email] #"@gmail.com$"]
```
