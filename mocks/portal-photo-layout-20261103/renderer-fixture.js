/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
// Local preview only. The production entry point and renderer remain unchanged.
(() => {
  const query = new URLSearchParams(location.search);
  const shapes = [
    [960,638,"landscape"],[480,720,"portrait"],[720,720,"square"],
    [1200,450,"panorama"],[480,720,"portrait-mountain"],[960,640,"valley"],
    [720,720,"square-forest"],[960,640,"mountain"],[480,720,"portrait-forest"],
  ];
  const mode = query.get("mode") === "framed" ? "framed" : "mosaic";
  let photos = shapes.map(([w,h,name]) => ({ w, h, url: `/samples/${name}.jpg` }));
  if (mode === "framed") photos = [photos[Number(query.get("photo")) || 0]];
  let seed = 531;
  Math.random = () => ((seed = (seed * 1664525 + 1013904223) >>> 0) / 4294967296);
  window.Android = {
    getMode: () => mode,
    getIntervalSec: () => 600,
    getPhotoMotion: () => query.get("motion") === "1",
    getPhotos: () => JSON.stringify(photos),
    paperSurfaceChanged: () => requestAnimationFrame(() => dispatchEvent(new Event("resize"))),
    getPhotoLayoutBounds: () => {
      const clock = document.querySelector("#chrome-clock");
      const rect = clock && clock.getBoundingClientRect();
      return JSON.stringify({
        width: innerWidth, height: innerHeight,
        occupied: rect ? [{ left: rect.left - 12, top: rect.top - 12, right: rect.right + 12, bottom: rect.bottom + 12 }] : [],
      });
    },
  };
})();
