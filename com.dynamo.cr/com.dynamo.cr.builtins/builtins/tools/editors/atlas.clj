(ns editors.atlas
  (:require [clojure.set :refer [difference union]]
            [clojure.string :as str]
            [plumbing.core :refer [fnk defnk]]
            [schema.core :as s]
            [schema.macros :as sm]
            [service.log :as log]
            [dynamo.background :as background]
            [dynamo.buffers :refer :all]
            [dynamo.camera :refer :all]
            [dynamo.editors :as ed]
            [dynamo.file :as file]
            [dynamo.file.protobuf :as protobuf :refer [pb->str]]
            [dynamo.geom :as g]
            [dynamo.gl :as gl]
            [dynamo.gl.shader :as shader]
            [dynamo.gl.texture :as texture]
            [dynamo.gl.vertex :as vtx]
            [dynamo.grid :as grid]
            [dynamo.image :refer :all]
            [dynamo.node :as n]
            [dynamo.project :as p]
            [dynamo.property :as dp]
            [dynamo.selection :as sel]
            [dynamo.system :as ds]
            [internal.ui.scene-editor :as ius]
            [dynamo.texture :as tex]
            [dynamo.types :as t :refer :all]
            [dynamo.ui :refer :all]
            [internal.render.pass :as pass])
  (:import  [com.dynamo.atlas.proto AtlasProto AtlasProto$Atlas AtlasProto$AtlasAnimation AtlasProto$AtlasImage]
            [com.dynamo.graphics.proto Graphics$TextureImage Graphics$TextureImage$Image Graphics$TextureImage$Type]
            [com.dynamo.textureset.proto TextureSetProto$Constants TextureSetProto$TextureSet TextureSetProto$TextureSetAnimation]
            [com.dynamo.tile.proto Tile$Playback]
            [com.jogamp.opengl.util.awt TextRenderer]
            [dynamo.types Animation Camera Image TexturePacking Rect EngineFormatTexture AABB]
            [java.awt.image BufferedImage]
            [javax.media.opengl GL GL2 GLContext GLDrawableFactory]
            [javax.media.opengl.glu GLU]
            [javax.vecmath Matrix4d]
            [org.eclipse.swt SWT]
            [org.eclipse.ui IEditorSite]))

(def integers (iterate (comp int inc) (int 0)))

(vtx/defvertex engine-format-texture
  (vec3.float position)
  (vec2.short texcoord0 true))

(vtx/defvertex texture-vtx
  (vec4 position)
  (vec2 texcoord0))

(vtx/defvertex uv-only
  (vec2 uv))

(declare tex-outline-vertices)

(n/defnode AnimationGroupNode
  (inherits n/OutlineNode)

  (input images [Image])

  (property fps             dp/NonNegativeInt (default 30))
  (property flip-horizontal s/Bool)
  (property flip-vertical   s/Bool)
  (property playback        AnimationPlayback (default :PLAYBACK_ONCE_FORWARD))

  (output animation Animation
    (fnk [this id images :- [Image] fps flip-horizontal flip-vertical playback]
      (->Animation id images fps flip-horizontal flip-vertical playback)))

  (property id s/Str))

(defn- consolidate
  [animations]
  (seq (into #{} (mapcat :images animations))))

(defn- basename [path]
  (-> path
      (str/split #"/")
      last
      (str/split #"\.(?=[^\.]+$)")
      first))

(defn- animation-from-image [image]
  (map->Animation {:id              (basename (:path image))
                   :images          [image]
                   :fps             30
                   :flip-horizontal 0
                   :flip-vertical   0
                   :playback        :PLAYBACK_ONCE_FORWARD}))

(defnk produce-texture-packing :- TexturePacking
  [this images :- [Image] animations :- [Animation] margin extrude-borders]
  (let [animations (concat animations (map animation-from-image images))
        texture-packing (tex/pack-textures margin extrude-borders (consolidate animations))]
    (assoc texture-packing :animations animations)))

(sm/defn build-atlas-image :- AtlasProto$AtlasImage
  [image :- Image]
  (.build (doto (AtlasProto$AtlasImage/newBuilder)
            (.setImage (str (.path image))))))

(sm/defn build-atlas-animation :- AtlasProto$AtlasAnimation
  [animation :- Animation]
  (.build (doto (AtlasProto$AtlasAnimation/newBuilder)
            (.addAllImages           (map build-atlas-image (.images animation)))
            (.setId                  (.id animation))
            (.setFps                 (.fps animation))
            (protobuf/set-if-present :flip-horizontal animation)
            (protobuf/set-if-present :flip-vertical animation)
            (protobuf/set-if-present :playback animation (partial protobuf/val->pb-enum Tile$Playback)))))

(defnk get-text-format :- s/Str
  "get the text string for this node"
  [this images :- [Image] animations :- [Animation]]
  (pb->str
    (.build
         (doto (AtlasProto$Atlas/newBuilder)
             (.addAllImages           (map build-atlas-image images))
             (.addAllAnimations       (map build-atlas-animation animations))
             (protobuf/set-if-present :margin this)
             (protobuf/set-if-present :extrude-borders this)))))

(defnk save-atlas-file
  [this filename]
  (let [text (n/get-node-value this :text-format)]
    (file/write-file filename (.getBytes text))
    :ok))

(defn vertex-starts [n-vertices] (take n-vertices (take-nth 6 integers)))
(defn vertex-counts [n-vertices] (take n-vertices (repeat (int 6))))

(defn render-overlay
  [ctx ^GL2 gl ^TextRenderer text-renderer texture-packing]
  (let [image ^BufferedImage (.packed-image texture-packing)]
    (gl/overlay ctx gl text-renderer (format "Size: %dx%d" (.getWidth image) (.getHeight image)) 12.0 -22.0 1.0 1.0)))

(shader/defshader pos-uv-vert
  (attribute vec4 position)
  (attribute vec2 texcoord0)
  (varying vec2 var_texcoord0)
  (defn void main []
    (setq gl_Position (* gl_ModelViewProjectionMatrix position))
    (setq var_texcoord0 texcoord0)))

(shader/defshader pos-uv-frag
  (varying vec2 var_texcoord0)
  (uniform sampler2D texture)
  (defn void main []
    (setq gl_FragColor (texture2D texture var_texcoord0.xy))))

(def atlas-shader (shader/make-shader pos-uv-vert pos-uv-frag))

(defn render-texture-packing
  [ctx gl texture-packing vertex-binding gpu-texture]
  (gl/with-enabled gl [gpu-texture atlas-shader vertex-binding]
    (shader/set-uniform atlas-shader gl "texture" (texture/texture-unit-index gpu-texture))
    (gl/gl-draw-arrays gl GL/GL_TRIANGLES 0 (* 6 (count (:coords texture-packing))))))

(defn render-quad
  [ctx gl texture-packing vertex-binding gpu-texture i]
  (gl/with-enabled gl [gpu-texture atlas-shader vertex-binding]
    (shader/set-uniform atlas-shader gl "texture" (texture/texture-unit-index gpu-texture))
    (gl/gl-draw-arrays gl GL/GL_TRIANGLES (* 6 i) 6)))

(defn selection-renderables
  [this texture-packing vertex-binding gpu-texture]
  (let [project-root (p/project-root-node this)]
    (map-indexed (fn [i rect]
                   {:world-transform g/Identity4d
                    :select-name (:_id (t/lookup project-root (:path rect)))
                    :render-fn (fn [ctx gl glu text-renderer] (render-quad ctx gl texture-packing vertex-binding gpu-texture i))})
                 (:coords texture-packing))))

(defn render-selection-outline
  [ctx ^GL2 gl this texture-packing rect]
  (let [bounds (:aabb texture-packing)
        {:keys [x y width height]} rect
        left x
        right (+ x width)
        bottom (- (:height bounds) y)
        top (- (:height bounds) (+ y height))]
    (.glColor3ub gl 75 -1 -117)  ; #4bff8b bright green
    (.glBegin gl GL2/GL_LINE_LOOP)
    (.glVertex2i gl left top)
    (.glVertex2i gl right top)
    (.glVertex2i gl right bottom)
    (.glVertex2i gl left bottom)
    (.glEnd gl)))

(defn selection-outline-renderables
  [this texture-packing selection]
  (let [project-root (p/project-root-node this)
        selected (set @selection)]
    (vec (keep
           (fn [rect]
             (let [node (t/lookup project-root (:path rect))]
               (when (selected (:_id node))
                 {:world-transform g/Identity4d
                  :render-fn (fn [ctx gl glu text-renderer]
                               (render-selection-outline ctx gl this texture-packing rect))})))
           (:coords texture-packing)))))

(defnk produce-renderable :- RenderData
  [this texture-packing selection vertex-binding gpu-texture]
  {pass/overlay
   [{:world-transform g/Identity4d
     :render-fn       (fn [ctx gl glu text-renderer] (render-overlay ctx gl text-renderer texture-packing))}]
   pass/transparent
   [{:world-transform g/Identity4d
     :render-fn       (fn [ctx gl glu text-renderer] (render-texture-packing ctx gl texture-packing vertex-binding gpu-texture))}]
   pass/outline
   (selection-outline-renderables this texture-packing selection)
   pass/selection
   (selection-renderables this texture-packing vertex-binding gpu-texture)})

(defnk produce-renderable-vertex-buffer
  [[:texture-packing aabb coords]]
  (let [vbuf       (->texture-vtx (* 6 (count coords)))
        x-scale    (/ 1.0 (.width aabb))
        y-scale    (/ 1.0 (.height aabb))]
    (doseq [coord coords]
      (let [w  (.width coord)
            h  (.height coord)
            x0 (.x coord)
            y0 (- (.height aabb) (.y coord)) ;; invert for screen
            x1 (+ x0 w)
            y1 (- (.height aabb) (+ (.y coord) h))
            u0 (* x0 x-scale)
            v0 (* y0 y-scale)
            u1 (* x1 x-scale)
            v1 (* y1 y-scale)]
        (doto vbuf
          (conj! [x0 y0 0 1 u0 (- 1 v0)])
          (conj! [x0 y1 0 1 u0 (- 1 v1)])
          (conj! [x1 y1 0 1 u1 (- 1 v1)])

          (conj! [x1 y1 0 1 u1 (- 1 v1)])
          (conj! [x1 y0 0 1 u1 (- 1 v0)])
          (conj! [x0 y0 0 1 u0 (- 1 v0)]))))
    (persistent! vbuf)))

(defnk produce-outline-vertex-buffer
  [[:texture-packing aabb coords]]
  (let [vbuf       (->texture-vtx (* 6 (count coords)))
        x-scale    (/ 1.0 (.width aabb))
        y-scale    (/ 1.0 (.height aabb))]
    (doseq [coord coords]
      (let [w  (.width coord)
            h  (.height coord)
            x0 (.x coord)
            y0 (- (.height aabb) (.y coord)) ;; invert for screen
            x1 (+ x0 w)
            y1 (- (.height aabb) (+ y0 h))
            u0 (* x0 x-scale)
            v0 (* y0 y-scale)
            u1 (* x1 x-scale)
            v1 (* y1 y-scale)]
        (doto vbuf
          (conj! [x0 y0 0 1 u0 (- 1 v0)])
          (conj! [x0 y1 0 1 u0 (- 1 v1)])
          (conj! [x1 y1 0 1 u1 (- 1 v1)])
          (conj! [x1 y0 0 1 u1 (- 1 v0)]))))
    (persistent! vbuf)))

(n/defnode AtlasRender
  (input gpu-texture s/Any)
  (input texture-packing s/Any)
  (input selection s/Any :inject)

  (output vertex-buffer s/Any         :cached produce-renderable-vertex-buffer)
  (output outline-vertex-buffer s/Any :cached produce-outline-vertex-buffer)
  (output vertex-binding s/Any        :cached (fnk [vertex-buffer] (vtx/use-with vertex-buffer atlas-shader)))
  (output renderable RenderData       produce-renderable))

(def ^:private outline-vertices-per-placement 4)
(def ^:private vertex-size (.getNumber TextureSetProto$Constants/VERTEX_SIZE))

(sm/defn texture-packing->texcoords
  [{:keys [coords] :as texture-packing}]
  (let [x-scale    (/ 1.0 (.getWidth (.packed-image texture-packing)))
        y-scale    (/ 1.0 (.getHeight (.packed-image texture-packing)))
        vbuf       (->uv-only (* 2 (count coords)))]
    (doseq [coord coords]
      (let [x0 (.x coord)
            y0 (.y coord)
            x1 (+ (.x coord) (.width coord))
            y1 (+ (.y coord) (.height coord))
            u0 (* x0 x-scale)
            u1 (* x1 x-scale)
            v0 (* y0 y-scale)
            v1 (* y1 y-scale)]
        (doto vbuf
          (conj! [u0 v0])
          (conj! [u1 v1]))))
    (persistent! vbuf)))

(sm/defn texture-packing->vertices
  [{:keys [coords] :as texture-packing}]
  (let [x-scale    (/ 1.0 (.getWidth (.packed-image texture-packing)))
        y-scale    (/ 1.0 (.getHeight (.packed-image texture-packing)))
        vbuf       (->engine-format-texture (* 6 (count coords)))]
    (doseq [coord coords]
      (let [x0 (.x coord)
            y0 (.y coord)
            x1 (+ (.x coord) (.width coord))
            y1 (+ (.y coord) (.height coord))
            w2 (* (.width coord) 0.5)
            h2 (* (.height coord) 0.5)
            u0 (g/to-short-uv (* x0 x-scale))
            u1 (g/to-short-uv (* x1 x-scale))
            v0 (g/to-short-uv (* y0 y-scale))
            v1 (g/to-short-uv (* y1 y-scale))]
        (doto vbuf
          (conj! [(- w2) (- h2) 0 u0 v1])
          (conj! [   w2  (- h2) 0 u1 v1])
          (conj! [   w2     h2  0 u1 v0])

          (conj! [(- w2) (- h2) 0 u0 v1])
          (conj! [   w2     h2  0 u1 v0])
          (conj! [(- w2)    h2  0 v0 v0]))))
    (persistent! vbuf)))

(sm/defn texture-packing->outline-vertices
  [texture-packing]
  (let [x-scale    (/ 1.0 (.getWidth (.packed-image texture-packing)))
        y-scale    (/ 1.0 (.getHeight (.packed-image texture-packing)))
        coords     (:coords texture-packing)
        bounds     (:aabb texture-packing)
        vbuf       (->engine-format-texture (* 6 (count coords)))]
    (doseq [coord coords]
      (let [x0 (.x coord)
            y0 (.y coord)
            x1 (+ (.x coord) (.width coord))
            y1 (+ (.y coord) (.height coord))
            w2 (* (.width coord) 0.5)
            h2 (* (.height coord) 0.5)
            u0 (g/to-short-uv (* x0 x-scale))
            u1 (g/to-short-uv (* x1 x-scale))
            v0 (g/to-short-uv (* y0 y-scale))
            v1 (g/to-short-uv (* y1 y-scale))]
        (doto vbuf
          (conj! [(- w2) (- h2) 0 u0 v1])
          (conj! [   w2  (- h2) 0 u1 v1])
          (conj! [   w2     h2  0 u1 v0])
          (conj! [(- w2)    h2  0 v0 v0]))))
    (persistent! vbuf)))

(defn build-animation
  [anim begin]
  (let [start     (int begin)
        end       (int (+ begin (* 6 (count (:images anim)))))]
    (.build
      (doto (TextureSetProto$TextureSetAnimation/newBuilder)
         (.setId                  (:id anim))
         (.setWidth               (int (:width  (first (:images anim)))))
         (.setHeight              (int (:height (first (:images anim)))))
         (.setStart               start)
         (.setEnd                 end)
         (protobuf/set-if-present :playback anim (partial protobuf/val->pb-enum Tile$Playback))
         (protobuf/set-if-present :fps anim)
         (protobuf/set-if-present :flip-horizontal anim)
         (protobuf/set-if-present :flip-vertical anim)
         (protobuf/set-if-present :is-animation anim)))))

(defn build-animations
  [start-idx aseq]
  (let [animations (remove #(empty? (:images %)) aseq)
        starts (into [start-idx] (map #(+ start-idx (* 6 (count (:images %)))) animations))]
    (map (fn [anim start] (build-animation anim start)) animations starts)))

(defn texturesetc-protocol-buffer
  [texture-name {:keys [coords] :as texture-packing}]
  #_(s/validate TexturePacking texture-packing)
  (let [anims      (remove #(empty? (:images %)) (.animations texture-packing))
        n-rects    (count coords)
        n-vertices (reduce + n-rects (map #(count (.images %)) anims))]
    (.build (doto (TextureSetProto$TextureSet/newBuilder)
            (.setTexture               texture-name)
            (.setTexCoords             (byte-pack (texture-packing->texcoords texture-packing)))
            (.addAllAnimations         (build-animations (* 6 n-rects) anims))

            (.addAllVertexStart        (vertex-starts n-vertices))
            (.addAllVertexCount        (vertex-counts n-vertices))
            (.setVertices              (byte-pack (texture-packing->vertices texture-packing)))

            (.addAllOutlineVertexStart (take n-vertices (take-nth 4 integers)))
            (.addAllOutlineVertexCount (take n-vertices (repeat (int 4))))
            (.setOutlineVertices       (byte-pack (texture-packing->outline-vertices texture-packing)))

            (.setTileCount             (int 0))))))

(defnk compile-texturesetc :- s/Bool
  [this g project texture-packing :- TexturePacking]
  (file/write-file (:textureset-filename this)
    (.toByteArray (texturesetc-protocol-buffer (:texture-name this) texture-packing)))
  :ok)

(defn- texturec-protocol-buffer
  [engine-format]
  (s/validate EngineFormatTexture engine-format)
  (.build (doto (Graphics$TextureImage/newBuilder)
            (.addAlternatives
              (doto (Graphics$TextureImage$Image/newBuilder)
                (.setWidth           (.width engine-format))
                (.setHeight          (.height engine-format))
                (.setOriginalWidth   (.original-width engine-format))
                (.setOriginalHeight  (.original-height engine-format))
                (.setFormat          (.format engine-format))
                (.setData            (byte-pack (.data engine-format)))
                (.addAllMipMapOffset (.mipmap-offsets engine-format))
                (.addAllMipMapSize   (.mipmap-sizes engine-format))))
            (.setType            (Graphics$TextureImage$Type/TYPE_2D))
            (.setCount           1))))

(defnk compile-texturec :- s/Bool
  [this g project texture-packing :- TexturePacking]
  (file/write-file (:texture-filename this)
    (.toByteArray (texturec-protocol-buffer (tex/->engine-format (:packed-image texture-packing)))))
  :ok)

(n/defnode TextureSave
  (input texture-packing TexturePacking)

  (property texture-filename    s/Str (default ""))
  (property texture-name        s/Str)
  (property textureset-filename s/Str (default ""))

  (output   texturec    s/Any :on-update compile-texturec)
  (output   texturesetc s/Any :on-update compile-texturesetc))

(defn find-nodes-at-point [this context x y]
  (let [[renderable-inputs view-camera] (n/get-node-inputs this :renderables :view-camera)
        renderables (apply merge-with concat renderable-inputs)
        pick-rect {:x x :y (- (:bottom (:viewport view-camera)) y) :width 1 :height 1}]
    (ius/selection-renderer context renderables view-camera pick-rect)))

(defn- not-camera-movement?
  "True if the event does not have keyboard modifier-keys for a
  camera movement action (CTRL or ALT). Note that this won't
  necessarily apply to mouse-up events because the modifier keys
  can be released before the mouse button."
  [event]
  (zero? (bit-and (:state-mask event) (bit-or SWT/CTRL SWT/ALT))))

(defn- selection-event?
  [event]
  (and (not-camera-movement? event)
       (= 1 (:button event))))

(defn- deselect-all
  [selection-node]
  (doseq [[node label] (ds/sources-of selection-node :selected-nodes)]
    (ds/disconnect node label selection-node :selected-nodes)))

(defn- select-nodes
  [selection-node nodes]
  (doseq [node nodes]
    (ds/connect node :self selection-node :selected-nodes)))

(defn- selection-mode
  "True if the event has keyboard-modifier keys for multi-select.
  On Mac: COMMAND or SHIFT.
  On non-Mac: CTRL or SHIFT."
  [event]
  ;; SWT/MOD1 maps to COMMAND on Mac and CTRL elsewhere
  (if (zero? (bit-and (:state-mask event) (bit-or SWT/MOD1 SWT/SHIFT)))
    :replace
    :toggle))

(defn- selected-node-ids
  [selection-node]
  (set (map (comp :_id first) (ds/sources-of selection-node :selected-nodes))))

(defn- toggle
  "Returns a new set by toggling the elements in the 'clicked' set
  between present/absent in the 'previous' set."
  [previous clicked]
   (union
     (difference previous clicked)
     (difference clicked previous)))

(n/defnode SelectionController
  (input glcontext GLContext :inject)
  (input renderables [t/RenderData])
  (input view-camera Camera)
  (input selection-node s/Any :inject)
  (input default-selection s/Any)
  (on :mouse-down
    (when (selection-event? event)
      (let [{:keys [x y]} event
            {:keys [world-ref]} self
            [glcontext selection-node default-selection]
              (n/get-node-inputs self :glcontext :selection-node :default-selection)
            previous (disj (selected-node-ids selection-node)
                       (:_id default-selection))
            clicked (set (find-nodes-at-point self glcontext x y))
            new-node-ids (case (selection-mode event)
                           :replace clicked
                           :toggle (toggle previous clicked))
            nodes (or (seq (map #(ds/node world-ref %) new-node-ids))
                    [default-selection])]
        (deselect-all selection-node)
        (select-nodes selection-node nodes)))))

(defn broadcast-event [this event]
  (let [[controllers] (n/get-node-inputs this :controllers)]
    (doseq [controller controllers]
      (t/process-one-event controller event))))

(n/defnode BroadcastController
  (input controllers [s/Any])
  (on :mouse-down (broadcast-event self event))
  (on :mouse-up (broadcast-event self event))
  (on :mouse-double-click (broadcast-event self event))
  (on :mouse-enter (broadcast-event self event))
  (on :mouse-exit (broadcast-event self event))
  (on :mouse-hover (broadcast-event self event))
  (on :mouse-move (broadcast-event self event))
  (on :mouse-wheel (broadcast-event self event))
  (on :key-down (broadcast-event self event))
  (on :key-up (broadcast-event self event)))

(defn on-edit
  [project-node ^IEditorSite editor-site atlas-node]
  (let [editor (n/construct ed/SceneEditor :name "editor")]
    (ds/in (ds/add editor)
        (let [atlas-render (ds/add (n/construct AtlasRender))
              background   (ds/add (n/construct background/Gradient))
              grid         (ds/add (n/construct grid/Grid))
              camera       (ds/add (n/construct CameraController :camera (make-camera :orthographic)))
              controller   (ds/add (n/construct BroadcastController))
              selector     (ds/add (n/construct SelectionController))]
          (ds/connect atlas-node   :texture-packing atlas-render :texture-packing)
          (ds/connect atlas-node   :gpu-texture     atlas-render :gpu-texture)
          (ds/connect atlas-node   :self            selector     :default-selection)
          (ds/connect camera       :camera          grid         :camera)
          (ds/connect camera       :camera          editor       :view-camera)
          (ds/connect camera       :self            controller   :controllers)
          (ds/connect selector     :self            controller   :controllers)
          (ds/connect atlas-render :renderable      selector     :renderables)
          (ds/connect camera       :camera          selector     :view-camera)
          (ds/connect controller   :self            editor       :controller)
          (ds/connect background   :renderable      editor       :renderables)
          (ds/connect atlas-render :renderable      editor       :renderables)
          (ds/connect grid         :renderable      editor       :renderables)
          (ds/connect atlas-node   :aabb            editor       :aabb))
        editor)))

(defn- bind-image-connections
  [img-node target-node]
  (when (:image (t/outputs img-node))
    (ds/connect img-node :image target-node :images))
  (when (:tree (t/outputs img-node))
    (ds/connect img-node :tree  target-node :children)))

(defn- bind-images
  [image-nodes target-node]
  (doseq [img image-nodes]
    (bind-image-connections img target-node)))

(defn construct-ancillary-nodes
  [self locator input]
  (let [atlas (protobuf/pb->map (protobuf/read-text AtlasProto$Atlas input))]
    (ds/set-property self :margin (:margin atlas))
    (ds/set-property self :extrude-borders (:extrude-borders atlas))
    (doseq [anim (:animations atlas)
            :let [anim-node (ds/add (apply n/construct AnimationGroupNode (mapcat identity (select-keys anim [:flip-horizontal :flip-vertical :fps :playback :id]))))]]
      (bind-images (map #(lookup locator (:image %)) (:images anim)) anim-node)
      (ds/connect anim-node :animation self :animations)
      (ds/connect anim-node :tree      self :children))
    (bind-images (map #(lookup locator (:image %)) (:images atlas)) self)
    self))

(defn remove-ancillary-nodes
  [self]
  (doseq [[animation-group _] (ds/sources-of self :animations)]
    (ds/delete animation-group))
  (doseq [[image _] (ds/sources-of self :images)]
    (ds/disconnect image :image self :images)
    (ds/disconnect image :tree  self :children)))

(defn construct-compiler
  [self]
  (let [path (:filename self)
        compiler (ds/add (n/construct TextureSave
                           :texture-name        (clojure.string/replace (local-path (replace-extension path "texturesetc")) "content/" "")
                           :textureset-filename (if (satisfies? file/ProjectRelative path) (file/in-build-directory (replace-extension path "texturesetc")) path)
                           :texture-filename    (if (satisfies? file/ProjectRelative path) (file/in-build-directory (replace-extension path "texturec")) path)))]
    (ds/connect self :texture-packing compiler :texture-packing)
    self))

(defn remove-compiler
  [self]
  (let [candidates (ds/nodes-consuming self :texture-packing)]
    (doseq [[compiler _]  (filter #(= TextureSave (t/node-type (first %))) candidates)]
      (ds/delete compiler))))

(n/defnode AtlasNode
  "This node represents an actual Atlas. It accepts a collection
   of images and animations. It emits a packed texture-packing.

   Inputs:
   images `[dynamo.types/Image]` - A collection of images that will be packed into the atlas.
   animations `[dynamo.types/Animation]` - A collection of animations that will be packed into the atlas.

   Properties:
   margin - Integer, must be zero or greater. The number of pixels of transparent space to leave between textures.
   extrude-borders - Integer, must be zero or greater. The number of pixels for which the outer edge of each texture will be duplicated.

   The margin fits outside the extruded border.

   Outputs
   aabb `dynamo.types.AABB` - The AABB of the packed texture, in pixel space.
   gpu-texture `Texture` - A wrapper for the BufferedImage with the actual pixels. Conforms to the right protocols so you can directly use this in rendering.
   text-format `String` - A saveable representation of the atlas, its animations, and images. Built as a text-formatted protocol buffer.
   texture-packing `[dynamo.types/TexturePacking]` - A data structure with full access to the original image bounds, their coordinates in the packed image, the BufferedImage, and outline coordinates.\"
   "
  (inherits n/OutlineNode)
  (inherits n/ResourceNode)
  (inherits n/Saveable)

  (input images [Image])
  (input animations [Animation])

  (property margin          dp/NonNegativeInt (default 0))
  (property extrude-borders dp/NonNegativeInt (default 0))
  (property filename (s/protocol PathManipulation) (visible false))

  (output aabb            AABB               (fnk [texture-packing] (g/rect->aabb (:aabb texture-packing))))
  (output gpu-texture     s/Any      :cached (fnk [texture-packing] (texture/image-texture (:packed-image texture-packing))))
  (output save            s/Keyword          save-atlas-file)
  (output text-format     s/Str              get-text-format)
  (output texture-packing TexturePacking :cached :substitute-value (tex/blank-texture-packing) produce-texture-packing)

  (on :load
    (doto self
      (construct-ancillary-nodes (:project event) (:filename self))
      (construct-compiler)
      (ds/set-property :dirty false)))

  (on :unload
    (doto self
      (remove-ancillary-nodes)
      (remove-compiler))))

(defn add-image
  [evt]
  (when-let [target (first (filter #(= AtlasNode (node-type %)) (sel/selected-nodes evt)))]
    (let [project-node  (p/project-enclosing target)
          images-to-add (p/select-resources project-node ["png" "jpg"] "Add Image(s)" true)]
      (ds/transactional
        (doseq [img images-to-add]
          (ds/connect img :image target :images)
          (ds/connect img :tree  target :children))))))

(defcommand add-image-command
  "com.dynamo.cr.menu-items.scene"
  "com.dynamo.cr.builtins.editors.atlas.add-image"
  "Add Image")

(defhandler add-image-handler add-image-command #'add-image)

(when (ds/in-transaction?)
  (p/register-editor "atlas" #'on-edit)
  (p/register-node-type "atlas" AtlasNode))
