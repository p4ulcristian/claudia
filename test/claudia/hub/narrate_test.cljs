(ns claudia.hub.narrate-test
  "Narration streams a clip as the voice makes it, and keeps it on disk only once it came whole."
  (:require [cljs.test :refer [deftest is async]]
            [claudia.hub.narrate :as narrate]
            ["fs" :as fs]
            ["http" :as http]
            ["os" :as os]
            ["path" :as path]))

(defn- bytes-of [^js b] (vec (array-seq (js/Uint8Array.from b))))

(defn- reader-of
  "A fake ReadableStream reader giving chunks (vectors of bytes), then breaking off with an error
  when broken? or ending."
  [chunks broken?]
  (let [left (atom chunks)]
    #js {:read (fn []
                 (if-let [c (first @left)]
                   (do (swap! left rest) (js/Promise.resolve #js {:done false :value (js/Uint8Array.from (clj->js c))}))
                   (if broken?
                     (js/Promise.reject (js/Error. "terminated"))
                     (js/Promise.resolve #js {:done true}))))}))

(deftest read-all-hands-on-each-chunk-and-joins-them
  (async done
         (let [seen (atom [])]
           (-> (narrate/read-all (reader-of [[1 2 3] [4 5]] false) #(swap! seen conj (bytes-of %)))
               (.then (fn [b]
                        (is (= [[1 2 3] [4 5]] @seen) "each chunk on as it came, odd ends and all")
                        (is (= [1 2 3 4 5] (bytes-of b)))
                        (narrate/read-all (reader-of [[1 2]] true) (fn [_]))))
               (.then (fn [_] (is false "a stream that breaks off is not taken as whole")))
               (.catch (fn [e] (is (= "terminated" (.-message e)))))
               (.then done)))))

(deftest whole-pcm-needs-a-sample
  (is (= [0 1] (bytes-of (narrate/whole-pcm (js/Buffer.from #js [0 1])))))
  (is (thrown? js/Error (narrate/whole-pcm (js/Buffer.alloc 1)))))

(defn- fake-voice!
  "A voice service on a free port: \"cut\" breaks off after its first chunk, anything else comes
  in two chunks (the first odd). calls counts what it was asked. -> Promise of the server."
  [calls]
  (let [server (http/createServer
                (fn [^js req ^js res]
                  (let [body (atom "")]
                    (.on req "data" #(swap! body str %))
                    (.on req "end" (fn []
                                     (swap! calls inc)
                                     (.writeHead res 200 #js {"content-type" "application/octet-stream"})
                                     (.write res (js/Buffer.from #js [1 2 3]))
                                     (if (re-find #"name=\"text\"\r\n\r\ncut" @body)
                                       (js/setTimeout #(.destroy res) 20)
                                       (js/setTimeout #(.end res (js/Buffer.from #js [4 5 6])) 20)))))))]
    (js/Promise. (fn [ok] (.listen server 0 "127.0.0.1" #(ok server))))))

(defn- kept [dir] (vec (.readdirSync fs (path/join dir "narration"))))

(defn- streams-then-keeps
  "A clip being made: its chunks go to the sink as they come, the whole of it to disk; a second
  caller meanwhile gets it whole; asked again it comes from disk."
  [c dir calls]
  (let [seen (atom [])]
    (-> (js/Promise.all #js [(narrate/clip c "hello" nil nil #(swap! seen conj (bytes-of %)))
                             (narrate/clip c "hello" nil nil #(is false "a waiting caller gets no chunks"))])
        (.then (fn [[a b]]
                 (is (= [1 2 3 4 5 6] (bytes-of a) (bytes-of b)))
                 (is (= [1 2 3 4 5 6] (flatten @seen)) "the chunks went on as they came")
                 (is (= 1 @calls) "made once")
                 (is (= 1 (count (kept dir))))
                 (narrate/clip c "hello" nil nil #(is false "a kept clip is not streamed"))))
        (.then (fn [a]
                 (is (= [1 2 3 4 5 6] (bytes-of a)))
                 (is (= 1 @calls) "from disk"))))))

(deftest a-clip-is-kept-only-once-it-came-whole
  (async done
         (let [dir (fs/mkdtempSync (path/join (os/tmpdir) "narrate-"))
               calls (atom 0)]
           (narrate/start! dir)
           (-> (fake-voice! calls)
               (.then (fn [^js server]
                        (let [c {:url (str "http://127.0.0.1:" (.-port (.address server))) :token "t"}]
                          (-> (streams-then-keeps c dir calls)
                              (.then #(narrate/clip c "cut" nil nil (fn [_])))
                              (.then (fn [_] (is false "a cut clip fails")))
                              (.catch (fn [_] (is (= 1 (count (kept dir))) "a cut clip leaves no file")))
                              (.then #(.close server))))))
               (.then done)))))
