// Shared photo library for the screensaver mocks. Uses Picsum with fixed seeds so every mock loads
// the SAME real photos with deliberately varied aspect ratios — the point is to show how each mode
// handles portraits vs landscapes vs squares vs panoramas on a wide screen.
//
// Each entry: { seed, w, h } — w/h are the *intrinsic* dimensions we request (aspect ratio matters,
// not absolute size). Picsum returns a real photo cropped to that ratio for the given seed.
window.PHOTOS = [
  // Landscapes (wide)
  { seed: "ridge",     w: 1600, h: 1067, kind: "landscape" },
  { seed: "harbor",    w: 1600, h: 1067, kind: "landscape" },
  { seed: "desert",    w: 1600, h: 1067, kind: "landscape" },
  { seed: "forest7",   w: 1600, h: 1067, kind: "landscape" },
  { seed: "coast22",   w: 1600, h: 1000, kind: "landscape" },
  { seed: "city88",    w: 1600, h: 1067, kind: "landscape" },
  // Portraits (tall)
  { seed: "portraitA", w: 1000, h: 1500, kind: "portrait" },
  { seed: "portraitB", w: 1000, h: 1500, kind: "portrait" },
  { seed: "portraitC", w: 1000, h: 1400, kind: "portrait" },
  { seed: "portraitD", w: 1000, h: 1500, kind: "portrait" },
  { seed: "waterfall", w: 1000, h: 1600, kind: "portrait" },
  { seed: "tower55",   w: 1000, h: 1500, kind: "portrait" },
  // Squares
  { seed: "square1",   w: 1200, h: 1200, kind: "square" },
  { seed: "square2",   w: 1200, h: 1200, kind: "square" },
  { seed: "square3",   w: 1200, h: 1200, kind: "square" },
  // Panoramas (ultra-wide)
  { seed: "pano1",     w: 2400, h: 900,  kind: "pano" },
  { seed: "pano2",     w: 2400, h: 800,  kind: "pano" },
];

// Build a Picsum URL for a photo at a requested pixel size (keeps the entry's aspect ratio).
window.photoUrl = function (p, longEdge) {
  const scale = longEdge / Math.max(p.w, p.h);
  const w = Math.round(p.w * scale);
  const h = Math.round(p.h * scale);
  return `https://picsum.photos/seed/${p.seed}/${w}/${h}`;
};

window.aspect = (p) => p.w / p.h;
window.byKind = (kind) => window.PHOTOS.filter((p) => p.kind === kind);
window.shuffled = (arr) => {
  const a = arr.slice();
  for (let i = a.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [a[i], a[j]] = [a[j], a[i]];
  }
  return a;
};
