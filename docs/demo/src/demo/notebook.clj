(ns demo.notebook
  (:require [demo.app :refer :all]))

(comment
  ;; Orders per status
  (stats))

(comment
  ;; Last 3 orders
  (recent-orders 3))

(comment
  ;; Inspect a customer in the tap> tree
  (tap> (find-customer "Ada")))

(comment
  ;; Cancel an order through the service bean
  (.cancelOrder (get-bean "orderService") 3))

(comment
  ;; Pending orders total
  (->> (recent-orders 100) (filter #(= :pending (:status %))) (map :total) (reduce +)))
