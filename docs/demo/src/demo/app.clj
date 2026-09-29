(ns demo.app
  "A pretend order-management app, just enough to show webrepl off.")

(System/setProperty "env.profile" "dev")

(defrecord Order [id customer total status])

(def ^:private orders
  (atom (into (sorted-map)
              (map-indexed (fn [i [c t s]] [(inc i) (->Order (inc i) c t s)])
                           [["Ada" 129.90 :paid] ["Linus" 42.00 :shipped] ["Grace" 310.50 :pending]
                            ["Rich" 87.25 :paid] ["Alan" 15.00 :shipped] ["Ada" 220.00 :pending]
                            ["Grace" 64.80 :paid] ["Linus" 499.99 :paid]]))))

(defn recent-orders
  "The `n` most recent orders, newest first."
  [n]
  (take n (reverse (vals @orders))))

(defn stats
  "Order count per status."
  []
  (frequencies (map :status (vals @orders))))

(defn find-customer
  "A customer with their address and full order history."
  [name]
  {:name    name
   :email   (str (.toLowerCase ^String name) "@example.com")
   :address {:street "12 Lambda Lane" :city "Lisp City" :zip "1958"}
   :orders  (filterv #(= name (:customer %)) (vals @orders))})

(definterface IOrderService
  (findOrder [id])
  (countPending [])
  (countByStatus [status])
  (cancelOrder [id]))

(deftype OrderService []
  IOrderService
  (findOrder [_ id] (get @orders id))
  (countPending [_] (count (filter #(= :pending (:status %)) (vals @orders))))
  (countByStatus [_ status] (count (filter #(= status (:status %)) (vals @orders))))
  (cancelOrder [_ id] (:status (get (swap! orders assoc-in [id :status] :cancelled) id))))

(defn get-bean
  "Looks a service up by name, the way you'd fetch a Spring bean."
  [bean-name]
  (case bean-name "orderService" (OrderService.)))
