(ns graph
  (:import (org.jfree.chart ChartPanel JFreeChart ChartFactory)
           (org.jfree.data.xy XYSeries XYSeriesCollection)
           (org.jfree.chart.plot PlotOrientation)
           (org.jfree.chart.axis NumberAxis)
           (org.jfree.ui ApplicationFrame)
           (java.io BufferedReader PrintWriter)
           (java.text NumberFormat)
           (javax.swing JFrame JPanel BoxLayout JLabel)
           (java.net ServerSocket Socket)
           (java.awt BasicStroke Dimension Color))
  (:use clojure.contrib.str-utils
        clojure.contrib.duck-streams
        swank)
  (:gen-class :name GraphIt
              :main true))


(def *server-port* 6666)
(def *redraw-delay-ms* 1000)

(def *data-gatherer* (agent []))

(def *max-readings* (atom Integer/MAX_VALUE))
(def *graphs* (atom {}))

(def *frame* (JFrame.))
(def *status-bar* (JLabel.))
(def *panel* (JPanel.))


(defn parse-datapoint [#^String s]
  (let [[graph & bits] (.split s ":")]
    (let [[time label num] (if (= (count bits) 2)
                             (concat [(System/currentTimeMillis)] bits)
                             (cons (BigInteger. #^String (first bits))
                                   (rest bits)))]
      {:graph graph
       :time time
       :line label
       :value (.parse (NumberFormat/getInstance) num)})))



(defn add-datapoint [point]
  "Add a new data point to our series, creating a new line as required."
  (send *data-gatherer* conj point))


(defmacro print-exceptions [& body]
  `(try ~@body
        (catch Exception e# (.printStackTrace e#))))


(defn make-chart [title y-axis dataset]
  (let [linechart (ChartFactory/createXYLineChart title
                                                  "Time"
                                                  y-axis
                                                  dataset
                                                  PlotOrientation/VERTICAL
                                                  true true false)]
    (.setAntiAlias linechart true)

    ;; Initialisation gumpf
    (let [xyplot (.getPlot linechart)]
      (doto xyplot
        (.setDomainPannable true)
        (.setRangePannable true)
        (.setDomainZeroBaselineVisible true)
        (.setRangeZeroBaselineVisible true)
        (.setBackgroundPaint (Color. 239 239 239)))
      (doto (.getRenderer xyplot)
        (.setStroke (BasicStroke. 2.0))
        (.setBaseShapesVisible true)
        (.setBaseShapesFilled true)
        (.setOutlineStroke (BasicStroke. 3.0))
        (.setDrawOutlines true))
      (doto (.getRangeAxis xyplot)
        (.setStandardTickUnits (NumberAxis/createIntegerTickUnits))))

    (doto (ChartPanel. linechart)
      (.setMouseWheelEnabled true)
      (.setPreferredSize (Dimension. 800 600)))))


(defn do-plot [values]
  (print-exceptions

   (.setText *status-bar* (str "Queue size: " (count values)))

   (doseq [{:keys [graph time line value]} values]

     (when-not (@*graphs* graph)
       (let [dataset (XYSeriesCollection.)
             chart (make-chart graph "" dataset)]
         (.add *panel* chart)
         (.revalidate *panel*)
         (swap! *graphs* assoc graph
                {:chart chart
                 :dataset dataset
                 :lines {}})))

     (when-not (get-in @*graphs* [graph :lines line])
       (let [new-line (doto (XYSeries. line)
                        (.setMaximumItemCount @*max-readings*))]
         (swap! *graphs* update-in
                [graph :lines]
                assoc line new-line)

         (.addSeries (get-in @*graphs* [graph :dataset])
                     new-line)))

     (.add #^XYSeries (get-in @*graphs* [graph :lines line])
           #^Number time #^Number value false))

   (doseq [#^XYSeries line (for [graph (vals @*graphs*)
                                 line (-> graph :lines vals)]
                             line)]
     (.fireSeriesChanged line))

   (send-off *agent* do-plot)
   (Thread/sleep *redraw-delay-ms*))
  [])


(defn handle-client [#^Socket client]
  (print-exceptions
   (with-open [#^BufferedReader in (reader (.getInputStream client))
               #^PrintWriter out (writer (.getOutputStream client))]
     (.println out "Exit with 'done'")
     (.flush out)
     (doseq [s (take-while #(not= % "done") (line-seq in))]
       (add-datapoint (parse-datapoint s)))))
  (.close client))



(defn handle-inputs []
  "Open a socket and read lines of input."
  (with-open [server (ServerSocket. *server-port*)]
    (println "Listening on :6666")
    (while true
      (print-exceptions
       (let [client (.accept server)]
         (.start (Thread. #(handle-client client))))))))


(defn run-plotter []
  (doto *status-bar*
    (.setHorizontalAlignment JLabel/LEFT))

  (doto *panel*
    (.setLayout (BoxLayout. *panel* BoxLayout/PAGE_AXIS))
    (.add *status-bar*))

  (doto *frame*
    (.setDefaultCloseOperation JFrame/EXIT_ON_CLOSE)
    (.add *panel*)
    (.setSize (Dimension. 1280 1024))
    (.setVisible true)))


(defn run [max-to-keep & _]
  (let [data-handler (agent nil)]
    (reset! *max-readings* (Integer. max-to-keep))
    (.start (Thread. handle-inputs))
    (send-off *data-gatherer* do-plot)
    (binding [*3 nil *2 nil *1 nil *e nil]
      (swank/start-server "/dev/null" :port 5005))
    (run-plotter)))


(defn -main [& args]
  (if (and (not= (count args) 0)
           (not= (count args) 1))
    (println "Usage: <me> max-to-keep")
    (apply run `[~@args ~Integer/MAX_VALUE])))
