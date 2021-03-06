;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; The Dactyl-ManuForm Keyboard — Opposable Thumb Edition              ;;
;; Keyboard Case Model — Main Body                                     ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;;; The main body is one of the predefined bodies of the application.
;;; It was once the only body, and only output.
;;; It is currently distinguished by its ability to include a rear housing, and
;;; other features not yet made general.

;;; The long-term plan is for it to have no distinguishing characteristics, at
;;; which point it will exist only as a parameter default, or disappear.

(ns dactyl-keyboard.cad.body.main
  (:require [scad-clj.model :as model]
            [scad-tarmi.core :refer [mean]]
            [scad-tarmi.maybe :as maybe]
            [scad-klupe.iso :as threaded]
            [scad-tarmi.util :refer [loft]]
            [dactyl-keyboard.cad.misc :as misc]
            [dactyl-keyboard.cad.place :as place]
            [dactyl-keyboard.cad.key :as key]
            [dactyl-keyboard.compass :as compass :refer [sharp-left sharp-right]]
            [dactyl-keyboard.param.access :as access :refer [most-specific compensator]]))


;;;;;;;;;;;;;;;;;;
;; Rear Housing ;;
;;;;;;;;;;;;;;;;;;

(defn rhousing-post [getopt]
  (let [xy (getopt :main-body :rear-housing :wall-thickness)]
    (model/cube xy xy (getopt :main-body :rear-housing :roof-thickness))))

(defn- rhousing-height
  "The precise height of (the center of) each top-level rhousing-post."
  [getopt]
  (- (getopt :main-body :rear-housing :height)
     (/ (getopt :main-body :rear-housing :roof-thickness) 2)))

(defn rhousing-properties
  "Derive characteristics from parameters for the rear housing."
  [getopt]
  (let [cluster (getopt :main-body :rear-housing :position :cluster)
        key-style (fn [coord] (most-specific getopt [:key-style] cluster coord))
        row (last (getopt :key-clusters :derived :by-cluster cluster :row-range))
        coords (getopt :key-clusters :derived :by-cluster cluster
                       :coordinates-by-row row)
        pairs (into [] (for [coord coords, side [:NNW :NNE]] [coord side]))
        getpos (fn [[coord side]]
                 (place/cluster-place getopt cluster coord
                   (place/mount-corner-offset getopt (key-style coord) side)))
        y-max (apply max (map #(second (getpos %)) pairs))
        getoffset (partial getopt :main-body :rear-housing :position :offsets)
        y-roof-s (+ y-max (getoffset :south))
        y-roof-n (+ y-roof-s (getoffset :north))
        z (rhousing-height getopt)
        roof-sw [(- (first (getpos (first pairs))) (getoffset :west)) y-roof-s z]
        roof-se [(+ (first (getpos (last pairs))) (getoffset :east)) y-roof-s z]
        roof-nw [(first roof-sw) y-roof-n z]
        roof-ne [(first roof-se) y-roof-n z]
        between (fn [a b] (mapv #(/ % 2) (mapv + a b)))]
   {:coordinate-corner-pairs pairs
    ;; [x y z] coordinates on the topmost part of the roof:
    :side {:N (between roof-nw roof-ne)
           :NE roof-ne
           :E (between roof-ne roof-se)
           :SE roof-se
           :S (between roof-se roof-sw)
           :SW roof-sw
           :W (between roof-sw roof-nw)
           :NW roof-nw}
    :end-coord {:W (first coords), :E (last coords)}}))

(defn- rhousing-roof
  "A cuboid shape between the four corners of the rear housing’s roof."
  [getopt]
  (let [get-side (partial getopt :main-body :rear-housing :derived :side)]
    (apply model/hull
      (map #(maybe/translate (get-side %) (rhousing-post getopt))
           [:NW :NE :SE :SW]))))

(defn rhousing-pillar-functions
  "Make functions that determine the exact positions of rear housing walls.
  This is an awkward combination of reckoning functions for building the
  bottom plate in 2D and placement functions for building the case walls in
  3D. Because they’re specialized, the ultimate return values are disturbingly
  different."
  ;; TODO: Refactor this along the lines of the central housing.
  [getopt]
  (let [cluster (getopt :main-body :rear-housing :position :cluster)
        cluster-pillar
          (fn [cardinal rhousing-turning-fn cluster-turning-fn]
            ;; Make a function for a part of the key cluster wall.
            (fn [reckon upper]
              (let [coord (getopt :main-body :rear-housing :derived :end-coord cardinal)
                    subject (if reckon [0 0 0] (key/web-post getopt))
                    ;; For reckoning, return a 3D coordinate vector.
                    ;; For building, return a sequence of web posts.
                    picker (if reckon #(first (take-last 2 %)) identity)]
                (picker
                  (place/wall-edge-sequence getopt cluster upper
                    [coord cardinal rhousing-turning-fn] subject)))))
        rhousing-pillar
          (fn [side]
            ;; Make a function for a part of the rear housing.
            ;; For reckoning, return a 3D coordinate vector.
            ;; For building, return a hull of housing cubes.
            {:pre [(compass/intermediates side)]}
            (fn [reckon upper]
              (let [subject (if reckon
                              (place/rhousing-vertex-offset getopt side)
                              (rhousing-post getopt))]
                (apply (if reckon mean model/hull)
                  (map #(place/rhousing-place getopt side % subject)
                       (if upper [0 1] [1]))))))]
    [(cluster-pillar :W sharp-right sharp-left)
     (rhousing-pillar :WSW)
     (rhousing-pillar :WNW)
     (rhousing-pillar :NNW)
     (rhousing-pillar :NNE)
     (rhousing-pillar :ENE)
     (rhousing-pillar :ESE)
     (cluster-pillar :E sharp-left sharp-right)]))

(defn- rhousing-wall-shape-level
  "The west, north and east walls of the rear housing with connections to the
  ordinary case wall."
  [getopt is-upper-level joiner]
  (loft
    (reduce
      (fn [coll function] (conj coll (joiner (function false is-upper-level))))
      []
      (rhousing-pillar-functions getopt))))

(defn- rhousing-outer-wall
  "The complete walls of the rear housing: Vertical walls and a bevelled upper
  level that meets the roof."
  [getopt]
  (model/union
    (rhousing-wall-shape-level getopt true identity)
    (rhousing-wall-shape-level getopt false misc/bottom-hull)))

(defn- rhousing-web
  "An extension of a key cluster’s webbing onto the roof of the rear housing."
  [getopt]
  (let [cluster (getopt :main-body :rear-housing :position :cluster)
        key-style (fn [coord] (most-specific getopt [:key-style] cluster coord))
        pos-corner (fn [coord side]
                     (place/cluster-place getopt cluster coord
                       (place/mount-corner-offset getopt (key-style coord) side)))
        sw (getopt :main-body :rear-housing :derived :side :SW)
        se (getopt :main-body :rear-housing :derived :side :SE)
        x (fn [coord side]
            (max (first sw)
                 (min (first (pos-corner coord side))
                      (first se))))
        y (second sw)
        z (rhousing-height getopt)]
   (loft
     (reduce
       (fn [coll [coord side]]
         (conj coll
           (model/hull
             (place/cluster-place getopt cluster coord
               (key/mount-corner-post getopt (key-style coord) side))
             (model/translate [(x coord side) y z]
               (rhousing-post getopt)))))
       []
       (getopt :main-body :rear-housing :derived :coordinate-corner-pairs)))))

(defn- rhousing-mount-place [getopt side shape]
  {:pre [(compass/cardinals side)]}
  (let [d (getopt :main-body :rear-housing :fasteners :bolt-properties :m-diameter)
        offset (getopt :main-body :rear-housing :fasteners
                 (side compass/short-to-long) :offset)
        n (getopt :main-body :rear-housing :position :offsets :north)
        t (getopt :main-body :rear-housing :roof-thickness)
        h (threaded/datum d :hex-nut-height)
        [sign base] (case side
                      :W [+ (getopt :main-body :rear-housing :derived :side :SW)]
                      :E [- (getopt :main-body :rear-housing :derived :side :SE)])
        near (mapv + base [(+ (- (sign offset)) (sign d)) d (/ (+ t h) -2)])
        far (mapv + near [0 (- n d d) 0])]
   (model/hull
     (model/translate near shape)
     (model/translate far shape))))

(defn- rhousing-mount-positive [getopt side]
  {:pre [(compass/cardinals side)]}
  (let [d (getopt :main-body :rear-housing :fasteners :bolt-properties :m-diameter)
        w (* 2.2 d)]
   (rhousing-mount-place getopt side
     (model/cube w w (threaded/datum d :hex-nut-height)))))

(defn- rhousing-mount-negative [getopt side]
  {:pre [(compass/cardinals side)]}
  (let [d (getopt :main-body :rear-housing :fasteners :bolt-properties :m-diameter)]
   (model/union
     (rhousing-mount-place getopt side
       (model/cylinder (/ d 2) 20))
     (if (getopt :main-body :rear-housing :fasteners :bosses)
       (rhousing-mount-place getopt side
         (threaded/nut {:m-diameter d :compensator (compensator getopt) :negative true}))))))

(defn rear-housing
  "A squarish box at the far end of a key cluster."
  [getopt]
  (let [prop (partial getopt :main-body :rear-housing :fasteners)
        pair (fn [function]
               (model/union
                 (when (prop :west :include) (function getopt :W))
                 (when (prop :east :include) (function getopt :E))))]
   (model/difference
     (model/union
       (rhousing-roof getopt)
       (rhousing-web getopt)
       (rhousing-outer-wall getopt)
       (if (prop :bosses) (pair rhousing-mount-positive)))
     (pair rhousing-mount-negative))))
