/* Portal music review mock: a simulated Android media session. No audio, no network. */
(() => {
  "use strict";
  const $ = (id) => document.getElementById(id);
  const TRACKS = Object.freeze([
    { title: "Low tide, early", artist: "Marrow & Vale", art: "tide", wash: "#7a4b3a", dur: 252 },
    { title: "Moss on the north wall", artist: "Ilse Brandt", art: "moss", wash: "#3f5a34", dur: 228 },
    { title: "Embers", artist: "The Quiet Assembly", art: "ember", wash: "#6e2c36", dur: 301 },
  ]);
  const LONG = { title: "Everything we left on the kitchen table that summer (live at the Roundhouse)", artist: "Marrow & Vale with the Northern Strings", art: "tide", wash: "#7a4b3a", dur: 412 };
  const ICON = { pause: '<path d="M7 5h3.5v14H7zM13.5 5H17v14h-3.5z"/>', play: '<path d="M8 5v14l11-7z"/>' };
  const SCREENS = { start: "s-start", idle: "s-idle", playing: "s-np", paused: "s-np", ambient: "s-amb", "ambient-paused": "s-amb", walkup: "s-amb" };

  let session = { index: 0, position: 84, playing: true };
  let view = "playing";
  let flags = new Set();

  const fmt = (s) => `${Math.floor(s / 60)}:${String(Math.floor(s % 60)).padStart(2, "0")}`;
  const track = () => (flags.has("long") ? LONG : TRACKS[session.index]);

  function readHash() {
    const parts = location.hash.replace(/^#/, "").split("&").filter(Boolean);
    const next = parts.find((p) => SCREENS[p]) || "playing";
    return { view: next, flags: new Set(parts.filter((p) => !SCREENS[p])) };
  }
  function writeHash() {
    location.hash = [view, ...flags].join("&");
  }

  function renderSession() {
    const t = track();
    const pct = `${(100 * Math.min(session.position, t.dur)) / t.dur}%`;
    const paused = !session.playing;
    document.documentElement.style.setProperty("--wash", t.wash);
    for (const el of [$("np-art"), $("card-art")]) el.className = `${el.id === "np-art" ? "np-art " : ""}art art--${t.art}`;
    $("np-art").setAttribute("aria-label", `Artwork for ${t.title}`);
    $("np-title").textContent = $("card-title").textContent = t.title;
    $("np-artist").textContent = t.artist;
    $("card-artist").textContent = paused ? `Paused, ${t.artist}` : t.artist;
    $("np-status").querySelector("span").textContent = paused ? "Paused" : "Playing";
    $("np-pos").textContent = fmt(session.position);
    $("np-dur").textContent = fmt(t.dur);
    $("np-rail").style.setProperty("--p", pct);
    $("np-rail").setAttribute("aria-valuetext", `${fmt(session.position)} of ${fmt(t.dur)}`);
    $("np-rail").classList.toggle("noseek", flags.has("noseek"));
    $("np-rail").setAttribute("aria-disabled", String(flags.has("noseek")));
    document.querySelector(".card-rail").style.setProperty("--p", pct);
    document.querySelectorAll(".ic-toggle").forEach((svg) => { svg.innerHTML = paused ? ICON.play : ICON.pause; });
    document.querySelectorAll('[data-act="toggle"]').forEach((b) => b.setAttribute("aria-label", paused ? "Play" : "Pause"));
    $("s-np").classList.toggle("paused", paused);
    $("s-amb").classList.toggle("paused", paused);
  }

  function render() {
    const root = document.documentElement;
    root.dataset.theme = flags.has("light") ? "light" : "dark";
    document.body.classList.toggle("clean", flags.has("clean"));
    if (view === "paused" || view === "ambient-paused") session = { ...session, playing: false };
    if (view === "playing" || view === "ambient" || view === "walkup") session = { ...session, playing: session.playing || view !== "walkup" };
    Object.values(SCREENS).forEach((id) => $(id).classList.remove("on"));
    $(SCREENS[view]).classList.add("on");
    $("s-amb").classList.toggle("walkup", view === "walkup");
    $("stage").classList.toggle("ambient-on", SCREENS[view] === "s-amb");
    document.querySelectorAll("[data-go]").forEach((b) => b.setAttribute("aria-pressed", String(b.dataset.go === view)));
    document.querySelectorAll("[data-flag]").forEach((b) => b.setAttribute("aria-pressed", String(flags.has(b.dataset.flag))));
    renderSession();
  }

  function act(name) {
    const count = TRACKS.length;
    if (name === "toggle") session = { ...session, playing: !session.playing };
    if (name === "next") session = { ...session, index: (session.index + 1) % count, position: 0 };
    if (name === "prev") session = session.position > 3 ? { ...session, position: 0 } : { ...session, index: (session.index + count - 1) % count, position: 0 };
    if (view === "paused" && session.playing) view = "playing";
    if (view === "playing" && !session.playing) view = "paused";
    if (view === "ambient-paused" && session.playing) view = "ambient";
    writeHash();
    render();
  }

  function seekFrom(event) {
    if (flags.has("noseek")) return;
    const rect = $("np-rail").getBoundingClientRect();
    const ratio = Math.min(1, Math.max(0, (event.clientX - rect.left) / rect.width));
    session = { ...session, position: Math.round(ratio * track().dur) };
    renderSession();
  }

  function fit() {
    const scale = Math.min(window.innerWidth / 1080, window.innerHeight / 720);
    document.documentElement.style.setProperty("--fit", String(scale));
  }

  document.addEventListener("click", (e) => {
    const go = e.target.closest("[data-go]");
    const flag = e.target.closest("[data-flag]");
    const control = e.target.closest("[data-act]");
    if (go) { view = go.dataset.go; writeHash(); }
    if (flag) { const f = new Set(flags); f.has(flag.dataset.flag) ? f.delete(flag.dataset.flag) : f.add(flag.dataset.flag); flags = f; writeHash(); }
    if (control) act(control.dataset.act);
    if (e.target.closest("[data-handoff]")) {
      $("sim").textContent = "Mock: this would open music.youtube.com in the Portal browser";
    }
    if (e.target.closest("#card") && view === "ambient") { view = "walkup"; writeHash(); }
  });
  $("np-rail").addEventListener("pointerdown", seekFrom);
  window.addEventListener("hashchange", () => { ({ view, flags } = readHash()); render(); });
  window.addEventListener("resize", fit);

  // Quiet progress: one second per second while "playing", stops when hidden.
  setInterval(() => {
    if (!session.playing || document.hidden) return;
    const dur = track().dur;
    session = { ...session, position: Math.min(dur, session.position + 1) };
    renderSession();
  }, 1000);

  ({ view, flags } = readHash());
  fit();
  render();
})();
