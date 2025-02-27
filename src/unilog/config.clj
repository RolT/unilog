(ns unilog.config
  "Small veneer on top of logback.
   Originally based on the logging initialization in riemann.
   Now diverged quite a bit.

   For configuration, a single public function is exposed: `start-logging!` which
   takes care of configuring logback, later logging is done through
   standard facilities, such as [clojure.tools.logging](https://github.com/clojure/tools.logging).

   Two extension mechanism are provided to add support for more appenders and encoders,
   see `build-appender` and `build-encoder` respectively"
  (:import org.slf4j.LoggerFactory
           org.slf4j.bridge.SLF4JBridgeHandler
           ch.qos.logback.classic.net.SocketAppender
           ch.qos.logback.classic.encoder.PatternLayoutEncoder
           ch.qos.logback.classic.Logger
           ch.qos.logback.classic.LoggerContext
           ch.qos.logback.classic.BasicConfigurator
           ch.qos.logback.classic.Level
           ch.qos.logback.core.spi.ContextAware
           ch.qos.logback.core.rolling.TriggeringPolicy
           ch.qos.logback.core.rolling.RollingPolicy
           ch.qos.logback.core.spi.LifeCycle
           ch.qos.logback.core.Appender
           ch.qos.logback.core.encoder.Encoder
           ch.qos.logback.core.ConsoleAppender
           ch.qos.logback.core.FileAppender
           ch.qos.logback.core.OutputStreamAppender
           ch.qos.logback.core.UnsynchronizedAppenderBase
           ch.qos.logback.core.rolling.TimeBasedRollingPolicy
           ch.qos.logback.core.rolling.SizeAndTimeBasedFNATP
           ch.qos.logback.core.rolling.FixedWindowRollingPolicy
           ch.qos.logback.core.rolling.SizeBasedTriggeringPolicy
           ch.qos.logback.core.rolling.RollingFileAppender
           ch.qos.logback.core.util.Duration
           ch.qos.logback.core.util.FileSize
           ch.qos.logback.core.net.SyslogOutputStream
           net.logstash.logback.encoder.LogstashEncoder
           ch.qos.logback.classic.spi.ILoggingEvent
           ch.qos.logback.core.filter.Filter
           ch.qos.logback.core.spi.FilterReply))

;; Configuration constants
;; =======================

(def levels
  "Logging level names to logback level association"
  {:all   Level/ALL
   :trace Level/TRACE
   :debug Level/DEBUG
   :info  Level/INFO
   :warn  Level/WARN
   :error Level/ERROR
   :off   Level/OFF})

(def default-pattern
  "Default pattern for PatternLayoutEncoder"
  "%p [%d] %t - %c %m%n")

(def default-encoder
  "Default encoder and pattern configuration"
  {:encoder :pattern
   :pattern  default-pattern})

(def default-configuration
  "A simple default logging configuration"
  {:pattern "%p [%d] %t - %c - %m%n"
   :external false
   :console true
   :files  []
   :level  "info"})


;; Open dispatch method to build appender configuration
;; ====================================================

(defmulti appender-config
  "Called by walking through each key/val pair in the main configuration
   map. This allows for early transformation of quick access keys such as:
   `:console`, `:file`, and `:files`"
  first)

(defmethod appender-config :default
  [_]
  nil)

(defmethod appender-config :console
  [[_ val]]
  (when (boolean val)
    (cond (string? val)  {:appender :console
                          :encoder  :pattern
                          :pattern  val}
          (map? val)     (-> (merge default-encoder val)
                             (update-in [:encoder] keyword)
                             (assoc :appender :console))
          :else          {:appender :console
                          :encoder  :pattern
                          :pattern  default-pattern})))

(defmethod appender-config :file
  [[_ val]]
  (cond (string? val) (-> default-encoder
                          (assoc :appender :file)
                          (assoc :file val))
        (map? val)    (-> (merge default-encoder val)
                          (update-in [:encoder] keyword)
                          (assoc :appender :file))
        :else         (throw (ex-info "invalid file appender config"
                                      {:config val}))))

(defmethod appender-config :files
  [[_ files]]
  (for [file files]
    (appender-config [:file file])))

(defmethod appender-config :appenders
  [[_ appenders]]
  (for [appender appenders
        :when (map? appender)]
    (-> appender
        (update-in [:encoder] keyword)
        (update-in [:appender] keyword))))

;; Open dispatch method to build encoders based on configuration
;; =============================================================

(defmulti build-encoder
  "Given a prepared configuration map, associate a prepared encoder
  to the `:encoder` key."
  :encoder)

(defmethod build-encoder :pattern
  [{:keys [pattern] :as config}]
  (let [encoder (doto (PatternLayoutEncoder.)
                  (.setPattern (or pattern default-pattern)))]
    (assoc config :encoder encoder)))

(defmethod build-encoder :json
  [config]
  (assoc config :encoder (LogstashEncoder.)))

(defmethod build-encoder :default
  [{:keys [appender] :as config}]
  (cond-> config
    (instance? OutputStreamAppender appender)
    (assoc :encoder (doto (PatternLayoutEncoder.)
                      (.setPattern default-pattern)))))

;;
;; Open dispatch to build a file rolling policy
;; ============================================

(defmulti build-rolling-policy
  "Given a configuration map, build a RollingPolicy instance."
  :type)

(defmethod build-rolling-policy :fixed-window
  [{:keys [file pattern max-index min-index]
    :or {max-index 5
         min-index 1
         pattern ".%i.gz"}}]
  (doto (FixedWindowRollingPolicy.)
    (.setFileNamePattern (str file pattern))
    (.setMinIndex (int min-index))
    (.setMaxIndex (int max-index))))

(defmethod build-rolling-policy :time-based
  [{:keys [file pattern max-history max-size]
    :or {max-history 5}}]
  (let [tbrp (TimeBasedRollingPolicy.)
        pattern (if pattern
                  pattern
                  (if max-size
                    ;; TimeBasedRollingPolicy has a compression issue
                    ;; http://jira.qos.ch/browse/LOGBACK-992
                    ".%d{yyyy-MM-dd}.%i"
                    ".%d{yyyy-MM-dd}"))]
    (when max-size
      (->> (doto (SizeAndTimeBasedFNATP.)
             (.setMaxFileSize (FileSize/valueOf (str max-size))))
           (.setTimeBasedFileNamingAndTriggeringPolicy tbrp)))
    (doto tbrp
      (.setFileNamePattern (str file pattern))
      (.setMaxHistory max-history))))

(defmethod build-rolling-policy :default
  [config]
  (throw (ex-info "Invalid rolling policy" {:config config})))

;;
;; Open dispatch to build a triggering policy for rolling files
;; ============================================================

(defmulti build-triggering-policy
  "Given a configuration map, build a TriggeringPolicy instance."
  :type)


(defmethod build-triggering-policy :size-based
  [{:keys [max-size]
    :or {max-size SizeBasedTriggeringPolicy/DEFAULT_MAX_FILE_SIZE}}]
  (doto (SizeBasedTriggeringPolicy.)
    (.setMaxFileSize (FileSize/valueOf (str max-size)))))

;; Open dispatch method to build appenders
;; =======================================

(defmulti build-appender
  "Given a prepared configuration map, associate a prepared appender
  to the `:appender` key."
  :appender)

(defmethod build-appender :console
  [config]
  (assoc config :appender (ConsoleAppender.)))

(defmethod build-appender :file
  [{:keys [file] :as config}]
  (assoc config :appender (doto (FileAppender.)
                            (.setFile file))))

(defmethod build-appender :socket
  [{:keys [remote-host port queue-size reconnection-delay event-delay-limit]
    :or {remote-host "localhost"
         port        2004
         queue-size  500
         reconnection-delay "10 seconds"
         event-delay-limit "10 seconds"}
    :as config}]
  (let [appender (SocketAppender.)]
    (.setRemoteHost appender remote-host)
    (.setPort appender (int port))
    (when queue-size
      (.setQueueSize appender (int queue-size)))
    (when reconnection-delay
      (.setReconnectionDelay appender (Duration/valueOf reconnection-delay)))
    (when event-delay-limit
      (.setEventDelayLimit appender (Duration/valueOf event-delay-limit)))
    (assoc config :appender appender)))

(defmethod build-appender :syslog
  [{:keys [host port] :or {host "localhost" port 514} :as config}]
  (assoc config :appender (doto (OutputStreamAppender.)
                            (.setOutputStream
                             (SyslogOutputStream. host (int port))))))

(defmethod build-appender :rolling-file
  [{:keys [rolling-policy triggering-policy file]
    :or {rolling-policy    :fixed-window
         triggering-policy :size-based}
    :as config}]
  (let [appender (RollingFileAppender.)
        format-policy (fn [type policy]
                        (cond
                          (keyword? policy) {:type policy}
                          (string? policy) {:type policy}
                          (map? policy) (update-in policy [:type] keyword)
                          :else
                          (throw (ex-info (format "invalid %s policy" type)
                                          {:config policy}))))
        format-policy (comp #(assoc % :file file) format-policy)
        rolling-policy (format-policy "rolling" rolling-policy)
        triggering-policy (format-policy "triggering" triggering-policy)]
    (.setFile appender file)
    (when-not (= :time-based (:type rolling-policy))
      (.setTriggeringPolicy appender
                            (build-triggering-policy triggering-policy)))
    (.setRollingPolicy appender (build-rolling-policy rolling-policy))
    (assoc config :appender appender)))

(defmethod build-appender :default
  [val]
  (throw (ex-info "invalid log appender configuration" {:config val})))

;;; build-filters
;;; =======================================
(def result-mapping {:accept  FilterReply/ACCEPT
                     :deny    FilterReply/DENY
                     :neutral FilterReply/NEUTRAL})

(defn make-log-property-filter
  [field pred match no-match]
  (let [match-enum    (result-mapping match)
        no-match-enum (result-mapping no-match)]
    (proxy [Filter] []
      (decide [^ILoggingEvent event]
        (let [mdc (.getMDCPropertyMap event)
              v   (.get mdc (name field))]
          (if (pred v)
            match-enum
            no-match-enum))))))

(defn build-filters
  [config]
  (let [{:keys [^Appender appender filters]} config]
    (when (instance? UnsynchronizedAppenderBase appender)
      (doseq [[field pred match no-match] filters]
        (let [^Filter f (make-log-property-filter field pred match no-match)]
          (.addFilter appender f))))
    config))

;;; start-appender!
;;; =======================================
(defmulti start-appender!
  "Start an appender according to appender type"
  (fn [appender context]
    (type appender)))

(defmethod start-appender! RollingFileAppender
  [^RollingFileAppender appender ^LoggerContext context]
  ;; The order of operations is important. If you change it, errors will occur.
  (.setContext appender context)
  (let [^RollingPolicy rp (.getRollingPolicy appender)]
    (.setParent rp  appender)
    (when (instance? ContextAware rp)
      (.setContext ^ContextAware rp context))
    (.start rp))
  (when-let [tp ^TriggeringPolicy (.getTriggeringPolicy appender)]
    ;; Since TimeBasedRollingPolicy can serve as a triggering policy,
    ;; start triggering policy only if it is not started already.
    (when-not (.isStarted tp)
      (when (instance? ContextAware tp)
        (.setContext ^ContextAware tp context))
      (.start tp)))
  (doseq [^Filter f (.getCopyOfAttachedFiltersList appender)]
    (.start f))
  (.start appender))

(defmethod start-appender! :default
  [appender context]
  (.setContext ^ContextAware appender ^LoggerContext context)
  (when (instance? UnsynchronizedAppenderBase appender)
    (doseq [^Filter f (.getCopyOfAttachedFiltersList ^UnsynchronizedAppenderBase appender)]
      (.start f)))
  (.start ^LifeCycle appender)
  appender)

(defn start-logging!
  "Initialize logback logging from a map.

   The map accepts the following keys as keywords
   - `:level`: Default level at which to log.
   - `:pattern`: The pattern to use for logging text messages
   - `:console`: Append messages to the console using a simple pattern
      layout. If value is a boolean, treat it as such and use a default
      encoder. If value is a string, treat it as a pattern and use
      a pattern encoder. If value is a map, expect encoder configuration
      in the map.
   - `:file`:  A file to log to. May either be a string, the log file, or
      a map which accepts optional encoder configuration.
   - `:files`: A list of either strings or maps. strings will create
      text files, maps are expected to contain a `:path` key as well
      as an optional `:json` which when present and true will switch
      the layout to a JSONEventLayout for the logger.
   - `:overrides`: A map of namespace or class-name to log level,
      this will supersede the global level.
   - `:external`: Do not proceed with configuration, this
      is useful when logging configuration is provided
      in a different manner (by supplying your own logback config file
      for instance).

   When called with no arguments, assume an empty map

example:

```clojure
{:console   true
 :level     \"info\"
 :files     [\"/var/log/app.log\"
             {:file \"/var/log/app-json.log\"
              :encoder json}]
 :overrides {\"some.namespace\" \"debug\"}}
```
  "
  ([raw-config]
   (let [config (merge default-configuration raw-config)
         {:keys [external level overrides]} config]
     (when-not external
       (SLF4JBridgeHandler/removeHandlersForRootLogger)
       (SLF4JBridgeHandler/install)
       (let [get-level #(get levels (some-> % keyword) Level/INFO)
             level     ^Level (get-level level)
             root      ^Logger (LoggerFactory/getLogger Logger/ROOT_LOGGER_NAME)
             context   ^LoggerContext (LoggerFactory/getILoggerFactory)
             configs   (->> (merge {:console true} config)
                            (map appender-config)
                            (flatten)
                            (remove nil?)
                            (map build-appender)
                            (map build-filters)
                            (map build-encoder))]

         (.detachAndStopAllAppenders root)

         (doseq [{:keys [^Encoder encoder ^Appender appender]} configs]
           (when (and (instance? OutputStreamAppender appender) encoder)
             (.setContext encoder context)
             (.start encoder)
             (.setEncoder ^OutputStreamAppender appender encoder))
           (start-appender! appender context)
           (.addAppender root appender))

         (.setLevel root level)
         (doseq [[logger-name level] overrides
                 :let [logger (LoggerFactory/getLogger (name logger-name))
                       level  (get-level level)]]
           (.setLevel ^Logger logger ^Level level))
         root))))
  ([]
   (start-logging! default-configuration)))
