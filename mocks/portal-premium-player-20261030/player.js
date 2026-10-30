/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
(() => {
  "use strict";
  const $ = (id) => document.getElementById(id);
  const paths = {
    play: '<path d="M9 5v14l11-7z" fill="currentColor" stroke="none"/>',
    pause: '<path d="M8 5v14M16 5v14" stroke-width="4"/>',
    next: '<path d="m5 5 10 7-10 7z" fill="currentColor" stroke="none"/><path d="M18 5v14" stroke-width="2.5"/>',
    previous: '<path d="m19 5-10 7 10 7z" fill="currentColor" stroke="none"/><path d="M6 5v14" stroke-width="2.5"/>',
    queue: '<path d="M4 6h12M4 12h9M4 18h9m5-7v9m0-9 4 1"/><circle cx="16.5" cy="20" r="2" fill="currentColor" stroke="none"/>',
    speaker: '<rect x="6" y="3" width="12" height="18" rx="3"/><circle cx="12" cy="15" r="3"/><circle cx="12" cy="7" r=".8" fill="currentColor" stroke="none"/>',
    volume: '<path d="M4 9h4l5-4v14l-5-4H4zm12-1a6 6 0 0 1 0 8m3-11a10 10 0 0 1 0 14"/>',
    chevron: '<path d="m9 5 7 7-7 7"/>',
    compact: '<rect x="3" y="4" width="18" height="16" rx="3"/><path d="M7 15h10"/>',
    expand: '<path d="M9 4H4v5m11-5h5v5M4 15v5h5m11-5v5h-5"/>',
    search: '<circle cx="10" cy="10" r="6"/><path d="m15 15 6 6"/>',
    close: '<path d="m6 6 12 12M6 18 18 6"/>',
    music: '<path d="M9 18V5l11-2v13M9 8l11-2"/><ellipse cx="6" cy="18" rx="3" ry="2"/><ellipse cx="17" cy="16" rx="3" ry="2"/>',
    link: '<path d="m9 15 6-6m-8 3-2 2a4 4 0 0 0 6 6l2-2m-2-12 2-2a4 4 0 0 1 6 6l-2 2"/>',
    check: '<path d="m5 12 4 4L19 6"/>',
    offline: '<path d="m3 3 18 18M4 8a14 14 0 0 1 4-2m4-1a14 14 0 0 1 8 3M7 12a8 8 0 0 1 6-2m4 2a8 8 0 0 1 0 0m-7 4a3 3 0 0 1 4 0"/><circle cx="12" cy="20" r="1" fill="currentColor" stroke="none"/>',
  };
  const icon = (name) => `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${paths[name] || paths.music}</svg>`;
  const tracks = [
    { id: "sun", title: "After the sun", artist: "Sora Fields", album: "The quiet hours", duration: 284, cover: "dunes" },
    { id: "blue", title: "Blue distance", artist: "North Atlantic", album: "Tidal", duration: 237, cover: "tidal" },
    { id: "window", title: "An open window", artist: "Emi & the City", album: "Room with a view", duration: 198, cover: "window" },
    { id: "still", title: "Still, here", artist: "Sora Fields", album: "The quiet hours", duration: 312, cover: "dunes" },
    { id: "orbit", title: "Small hours", artist: "Low Orbit", album: "Soft landing", duration: 265, cover: "orbit" },
    { id: "home", title: "The long way home", artist: "June Assembly", album: "Field notes", duration: 246, cover: "field" },
    { id: "water", title: "Waterline", artist: "North Atlantic", album: "Tidal", duration: 224, cover: "tidal" },
    { id: "glass", title: "Glasshouse", artist: "Emi & the City", album: "Room with a view", duration: 203, cover: "window" },
    { id: "bloom", title: "Slow bloom", artist: "June Assembly", album: "Field notes", duration: 278, cover: "field" },
    { id: "rose", title: "Rosewood", artist: "Aster Vale", album: "In the shape of light", duration: 291, cover: "rose" },
    { id: "satellite", title: "Satellite lullaby", artist: "Low Orbit", album: "Soft landing", duration: 302, cover: "orbit" },
    { id: "light", title: "In the shape of light", artist: "Aster Vale", album: "In the shape of light", duration: 216, cover: "rose" },
  ];
  const playlists = [
    { id: "quiet", name: "The quiet hours", description: "Warm textures. Unhurried evenings.", cover: "dunes", ids: ["sun", "blue", "window", "still", "orbit", "home"] },
    { id: "coast", name: "A slower morning", description: "Soft light and a fresh start.", cover: "tidal", ids: ["water", "glass", "bloom", "blue", "light"] },
    { id: "late", name: "After everyone sleeps", description: "A little further from the everyday.", cover: "orbit", ids: ["orbit", "rose", "satellite", "still", "light"] },
  ];
  const initialRooms = () => [
    { id: "living", name: "Living room", volume: 42, available: true, controllable: true, compatible: true, grouped: true },
    { id: "kitchen", name: "Kitchen", volume: 28, available: true, controllable: true, compatible: true, grouped: false },
    { id: "study", name: "Study", volume: null, available: false, controllable: false, compatible: false, grouped: false },
  ];
  const initialState = () => ({
    view: "now", compact: false, panel: "queue", scenario: "ready", playing: false,
    position: 83, trackId: "sun", queue: [...playlists[0].ids], playlistId: "quiet",
    rooms: initialRooms(), selectedRoom: "living", search: "", detail: null,
  });
  let state = initialState();
  let tick = null, toastTimeout = null, lastTime = 0;
  const track = () => tracks.find((item) => item.id === state.trackId) || tracks[0];
  const playlist = () => playlists.find((item) => item.id === state.playlistId) || playlists[0];
  const time = (seconds) => `${Math.floor(seconds / 60)}:${String(Math.floor(seconds % 60)).padStart(2, "0")}`;
  const escape = (value) => String(value).replace(/[&<>"']/g, (char) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[char]));
  const connectionReady = () => ["ready", "local", "live", "unknown", "emptyqueue", "interrupted", "failed"].includes(state.scenario);
  const isLocal = () => state.scenario === "local";
  const canBrowse = () => connectionReady() && !isLocal();
  const canStart = () => connectionReady() && state.scenario !== "interrupted";
  const canSkip = () => canStart() && state.scenario !== "live";
  const canSeek = () => canSkip() && state.scenario !== "unknown";
  const canQueue = () => connectionReady() && !isLocal() && state.scenario !== "live";
  const canRooms = () => connectionReady() && !isLocal();
  const toast = (text) => {
    $("toast").textContent = text; $("toast").hidden = false;
    clearTimeout(toastTimeout);
    toastTimeout = setTimeout(() => { $("toast").hidden = true; }, 2800);
  };
  const fill = (id, glyph) => { $(id).innerHTML = icon(glyph); };
  const row = (item, current, context = "queue") => {
    const enabled = context === "queue" ? canQueue() && canStart() : canBrowse();
    return `<button class="track-row ${current ? "current" : ""}" data-track="${item.id}" data-context="${context}" ${enabled ? "" : "disabled"} ${current ? 'aria-current="true"' : ""} aria-label="${escape(context === "queue" ? "Select" : "Preview")} ${escape(item.title)} by ${escape(item.artist)}">
      <img src="assets/${item.cover}.svg" alt="" width="80" height="80" loading="lazy">
      <span class="row-text"><strong>${escape(item.title)}</strong><small>${current ? (state.playing ? "Preview playing" : "Selected") + " · " : ""}${escape(item.artist)}</small></span>
      <span class="row-duration">${time(item.duration)}</span><span class="row-indicator">${current ? icon(state.playing ? "volume" : "pause") : ""}</span>
    </button>`;
  };
  function renderProgress() {
    const duration = track().duration;
    const position = Math.min(duration, state.position);
    $("seek").max = duration;
    $("seek").value = Math.floor(position);
    $("seek").style.setProperty("--progress", `${100 * position / duration}%`);
    $("seek").setAttribute("aria-valuetext", `${time(position)} of ${time(duration)}`);
    $("elapsed").textContent = time(position);
    $("remaining").textContent = `−${time(duration - position)}`;
  }
  function renderTrack() {
    const current = track();
    const live = state.scenario === "live";
    const title = live ? "Slow radio" : current.title;
    const artist = live ? "Sample live station" : current.artist;
    const status = state.scenario === "offline" ? "Last known state" : state.playing ? "Preview playing" : "Preview paused";
    $("track-title").textContent = title;
    $("track-artist").textContent = artist;
    $("track-album").textContent = live ? "Live fixture · duration unavailable" : current.album;
    $("playback-status").textContent = status;
    $("main-cover").src = `assets/${live ? "tidal" : current.cover}.svg`;
    $("main-cover").alt = `${title}: original ${live ? "tidal" : current.cover} cover artwork`;
    $("compact-cover").src = $("main-cover").src;
    $("compact-title").textContent = title;
    $("compact-artist").textContent = `${artist} · ${status}`;
    const canToggle = state.playing ? connectionReady() : canStart();
    ["play", "compact-play"].forEach((id) => {
      fill(id, state.playing ? "pause" : "play");
      $(id).disabled = !canToggle;
      $(id).setAttribute("aria-label", state.playing ? "Simulate pause" : "Simulate play");
    });
    ["previous", "next", "compact-previous", "compact-next"].forEach((id) => { $(id).disabled = !canSkip(); });
    $("seek-block").hidden = live || state.scenario === "unknown";
    $("seek").disabled = !canSeek();
    $("timeline-message").hidden = !live && state.scenario !== "unknown";
    $("timeline-message").textContent = live ? "Live station · Seeking unavailable" : "Track duration unavailable · Seeking unavailable";
    $("output-label").textContent = isLocal() ? "Local media session · Volume unavailable" : state.rooms.find((room) => room.id === state.selectedRoom).name;
    $("output").disabled = !canRooms();
    renderProgress();
  }
  function renderQueue() {
    const ids = state.scenario === "emptyqueue" ? [] : state.queue;
    $("queue-count").textContent = canQueue() ? ids.length : "—";
    $("queue-description").textContent = `Sample playlist · ${ids.length} tracks`;
    document.querySelector("#queue-panel h2").textContent = playlist().name;
    $("queue-summary").textContent = `${time(ids.reduce((total, id) => total + tracks.find((item) => item.id === id).duration, 0))} of sample music`;
    if (!canQueue()) {
      $("queue-list").innerHTML = `<div class="panel-empty">${icon("queue")}<h3>Queue unavailable</h3><p>${isLocal() ? "This local session provides transport controls only. Choose music in its own app." : state.scenario === "live" ? "This live station does not provide a song queue." : "The player’s queue appears after the connection is ready."}</p></div>`;
    } else if (!ids.length) {
      $("queue-list").innerHTML = `<div class="panel-empty">${icon("queue")}<h3>A little quiet, for now</h3><p>Choose a sample playlist to explore the queue.</p><button class="primary-button" data-view="playlists">Explore playlists</button></div>`;
    } else {
      $("queue-list").innerHTML = ids.map((id) => row(tracks.find((item) => item.id === id), id === state.trackId)).join("");
    }
    document.querySelector(".queue-footer [data-view]").disabled = !canBrowse();
  }
  function renderSpeakers() {
    const leader = state.rooms.find((room) => room.id === state.selectedRoom);
    $("speaker-list").innerHTML = state.rooms.map((room) => {
      const active = room.id === state.selectedRoom;
      const inGroup = active || room.grouped;
      const canSelect = canRooms() && room.available;
      const canGroup = canRooms() && room.available && room.compatible && !active && (inGroup || state.scenario !== "interrupted");
      const label = !room.available ? "Offline" : active ? "Selected player" : inGroup ? `Grouped with ${leader.name}` : "Available";
      return `<article class="speaker-card ${active ? "active" : ""}">
        <div class="speaker-top"><span class="speaker-symbol">${icon("speaker")}</span><div class="speaker-meta"><h3>${room.name}</h3><p>${label}</p></div><button class="text-button" data-room="${room.id}" ${canSelect && !active ? "" : "disabled"}>${active ? "Selected" : "Use player"}</button></div>
        ${room.volume !== null ? `<div class="speaker-volume">${icon("volume")}<label class="sr-only" for="volume-${room.id}">${room.name} volume</label><input id="volume-${room.id}" type="range" min="0" max="100" value="${room.volume}" data-volume="${room.id}" style="--progress:${room.volume}%" ${canRooms() && room.available && room.controllable ? "" : "disabled"}><output for="volume-${room.id}">${room.volume}%</output></div>` : '<p class="panel-footnote">Volume unavailable</p>'}
        ${!active && room.available ? `<button class="text-button group-button" data-group="${room.id}" ${canGroup ? "" : "disabled"}>${icon(inGroup ? "check" : "link")}${inGroup ? "Remove from group" : "Join compatible group"}</button>` : ""}
      </article>`;
    }).join("");
  }
  function renderLibrary() {
    const query = state.search.toLowerCase().trim();
    const matching = tracks.filter((item) => `${item.title} ${item.artist} ${item.album}`.toLowerCase().includes(query));
    const albums = [...new Map(matching.map((item) => [item.album, item])).values()];
    $("album-grid").innerHTML = albums.map((item) => `<button class="album-card" data-album="${escape(item.album)}" ${canBrowse() ? "" : "disabled"} aria-label="Explore sample album ${escape(item.album)}"><img src="assets/${item.cover}.svg" alt="Original ${item.cover} cover art" width="300" height="300" loading="lazy"><strong>${escape(item.album)}</strong><small>${escape(item.artist)}</small></button>`).join("");
    $("library-count").textContent = `${matching.length} sample tracks`;
    $("library-list").innerHTML = matching.length ? matching.map((item) => row(item, item.id === state.trackId, "library")).join("") : '<div class="panel-empty"><h3>No matching tracks</h3><p>Try another title, artist or album.</p></div>';
  }
  function renderPlaylists() {
    $("playlist-grid").innerHTML = playlists.map((list) => `<button class="playlist-card" data-playlist="${list.id}" ${canBrowse() ? "" : "disabled"} aria-label="Open sample playlist ${list.name}"><img src="assets/${list.cover}.svg" alt="Original ${list.cover} playlist art" width="600" height="400"><div><h2>${list.name}</h2><p>${list.description}</p><span>${list.ids.length} tracks · ${time(list.ids.reduce((sum, id) => sum + tracks.find((item) => item.id === id).duration, 0))}</span></div></button>`).join("");
    $("playlist-detail").hidden = !state.detail;
    if (state.detail) {
      const list = playlists.find((item) => item.id === state.detail);
      $("playlist-detail").innerHTML = `<div class="playlist-detail-heading"><div><h2>${list.name}</h2><p>Sample playlist · No audio playback</p></div><button class="primary-button" data-use-playlist="${list.id}" ${canBrowse() ? "" : "disabled"}>Preview this playlist</button></div>${list.ids.map((id) => row(tracks.find((item) => item.id === id), id === state.trackId, "playlist")).join("")}`;
    }
  }
  const notices = {
    offline: ["Player is offline", "Last known track. Controls return when the player reconnects."],
    auth: ["Authorization required", "Open Music settings to authorize your connection."],
    incompatible: ["This server is incompatible", "Check the supported server version in Music settings."],
    interrupted: ["Playback is interrupted", "Starting music, seeking and queue selection are unavailable. Supported pause and volume controls remain available."],
    failed: ["The player could not apply the change", "The sample track has been kept. Review the connection before trying again."],
  };
  function renderAvailability() {
    const noTrack = ["notconfigured", "connecting", "auth", "incompatible"].includes(state.scenario);
    const night = state.view === "night-clock";
    $("night-clock-view").hidden = !night;
    document.querySelector(".topbar").hidden = night;
    document.querySelector(".preview-toolbar").hidden = night;
    $("empty-view").hidden = night || !noTrack;
    ["now", "library", "playlists"].forEach((view) => { $(`${view}-view`).hidden = noTrack || state.compact || state.view !== view; });
    $("compact-view").hidden = night || noTrack || !state.compact;
    const notice = notices[state.scenario];
    $("notice").hidden = !notice;
    if (notice) $("notice").innerHTML = `<div><strong>${notice[0]}</strong><span>${notice[1]}</span></div><button class="text-button" data-settings>Connection info</button>`;
    if (noTrack) {
      const content = {
        notconfigured: ["Your music starts here", "Connect a supported music provider or start music in a supported media app.", "View connection information"],
        connecting: ["Connecting to your player", "Controls become available once the player supplies its current state.", "View connection information"],
        auth: ["Your connection needs authorization", "Authorize the connection in Music settings, then return to your player.", "View connection information"],
        incompatible: ["A supported connection is needed", "Check your music server’s version in Music settings.", "View connection information"],
      }[state.scenario];
      $("empty-view").innerHTML = `<div class="empty-icon">${icon("music")}</div><h1>${content[0]}</h1><p>${content[1]}</p><button class="primary-button" data-settings>${content[2]}</button><p class="empty-detail">Preview state · No connection attempt is made.</p>`;
    }
    document.querySelectorAll(".main-nav [data-view]").forEach((button) => {
      const selected = state.view === button.dataset.view;
      button.classList.toggle("selected", selected);
      if (selected) button.setAttribute("aria-current", "page"); else button.removeAttribute("aria-current");
      button.disabled = !["now", "night-clock"].includes(button.dataset.view) && !canBrowse();
    });
    $("compact-toggle").disabled = noTrack;
    $("compact-toggle").setAttribute("aria-label", state.compact ? "Open full player" : "Switch to compact player");
    fill("compact-toggle", state.compact ? "expand" : "compact");
    $("fixture-note").textContent = isLocal() ? "Local fixture · Library, queue and speaker controls are unavailable." : "All tracks, playlists and speakers are fixtures.";
    if (night) drawNightClock();
  }
  function manageTick() {
    if (tick) clearInterval(tick);
    tick = null;
    if (!state.playing || !connectionReady() || state.compact || state.view !== "now" || document.hidden || state.scenario === "live" || state.scenario === "unknown") return;
    lastTime = performance.now();
    tick = setInterval(() => {
      const now = performance.now();
      state.position = Math.min(track().duration, state.position + (now - lastTime) / 1000);
      lastTime = now;
      renderProgress();
      // A fixture stops at its end. It never starts another track without a user action.
      if (state.position >= track().duration) { state.playing = false; render(); }
    }, 500);
  }
  function render() {
    renderTrack(); renderQueue(); renderSpeakers(); renderLibrary(); renderPlaylists(); renderAvailability();
    ["queue", "speakers"].forEach((panel) => {
      $(`${panel}-panel`).hidden = state.panel !== panel;
      $(`${panel}-tab`).classList.toggle("selected", state.panel === panel);
      $(`${panel}-tab`).setAttribute("aria-selected", String(state.panel === panel));
      $(`${panel}-tab`).tabIndex = state.panel === panel ? 0 : -1;
    });
    $("scenario").value = state.scenario;
    history.replaceState(null, "", `#${state.view}`);
    manageTick();
  }
  function drawNightClock() {
    const canvas = $("night-canvas");
    // A single native-resolution surface. No blur, seconds, animation or sensor polling.
    const width = innerWidth, height = innerHeight;
    canvas.width = width; canvas.height = height;
    const ctx = canvas.getContext("2d");
    const red = $("night-ink").value === "red";
    const ink = red ? "166,85,72" : "185,159,135";
    const clock = $("night-format").value === "24" ? "22:08" : "10:08";
    const date = "Friday, October 30";
    ctx.fillStyle = "#000"; ctx.fillRect(0, 0, width, height);
    ctx.textAlign = "center"; ctx.textBaseline = "alphabetic";
    ctx.font = `300 ${height * .28}px "Helvetica Neue", Arial, sans-serif`;
    // Preserve the native 28% size on Portal; fit only unusually narrow portrait previews.
    const requestedWidth = ctx.measureText(clock).width;
    if (requestedWidth > width * .82) ctx.font = `300 ${height * .28 * width * .82 / requestedWidth}px "Helvetica Neue", Arial, sans-serif`;
    const clockMetrics = ctx.measureText(clock);
    const clockAscent = clockMetrics.fontBoundingBoxAscent ?? clockMetrics.actualBoundingBoxAscent;
    const clockDescent = clockMetrics.fontBoundingBoxDescent ?? clockMetrics.actualBoundingBoxDescent;
    const clockHeight = clockAscent + clockDescent;
    const clockFont = ctx.font;
    const requestedDateSize = Math.min(height * .041, width * .038);
    ctx.font = `400 ${requestedDateSize}px "Helvetica Neue", Arial, sans-serif`;
    const requestedDateWidth = ctx.measureText(date).width;
    if (requestedDateWidth > width * .84) ctx.font = `400 ${requestedDateSize * width * .84 / requestedDateWidth}px "Helvetica Neue", Arial, sans-serif`;
    const dateMetrics = ctx.measureText(date);
    const dateAscent = dateMetrics.fontBoundingBoxAscent ?? dateMetrics.actualBoundingBoxAscent;
    const dateDescent = dateMetrics.fontBoundingBoxDescent ?? dateMetrics.actualBoundingBoxDescent;
    const dateHeight = dateAscent + dateDescent;
    const gap = height * .043;
    const top = (height - clockHeight - dateHeight - gap) / 2;
    const dateFont = ctx.font;
    ctx.font = clockFont; ctx.fillStyle = `rgb(${ink})`;
    ctx.fillText(clock, width / 2, top + clockAscent);
    ctx.font = dateFont; ctx.fillStyle = `rgba(${ink},${180 / 255})`;
    ctx.fillText(date, width / 2, top + clockHeight + gap + dateAscent);
    canvas.dataset.clockFont = clockFont;
    canvas.dataset.dateFont = dateFont;
    canvas.dataset.gap = String(gap);
    canvas.dataset.ink = ink;
    canvas.setAttribute("aria-label", `Sample night clock: ${clock}, ${date}. ${red ? "Red" : "Warm"} ink.`);
    $("night-clock-view").style.color = `rgb(${ink})`;
  }
  function selectTrack(id, context) {
    if ((context === "queue" && (!canQueue() || !canStart())) || (context !== "queue" && !canBrowse())) return;
    if (!tracks.some((item) => item.id === id)) return;
    if (state.scenario === "failed" && context === "queue") { toast("Simulated command failure. Sample state unchanged."); return; }
    // Library selections construct only a sample queue, never a provider command.
    if (context !== "queue" && !state.queue.includes(id)) state.queue = [id, ...state.queue];
    state.trackId = id; state.position = 0; state.playing = false; state.view = "now"; state.compact = false;
    if (state.scenario === "emptyqueue") state.scenario = "ready";
    render(); toast(`Selected sample: ${track().title}. No audio output.`);
  }
  function skip(direction) {
    if (!canSkip()) return;
    if (state.scenario === "failed") { toast("Simulated command failure. Sample state unchanged."); return; }
    if (direction < 0 && state.position > 3) { state.position = 0; renderProgress(); toast("Sample track restarted."); return; }
    const index = state.queue.indexOf(state.trackId);
    const next = Math.max(0, Math.min(state.queue.length - 1, index + direction));
    if (state.queue[next]) { state.trackId = state.queue[next]; state.position = 0; render(); toast(`Sample ${direction > 0 ? "next" : "previous"} track: ${track().title}`); }
  }
  function togglePlayback() {
    if (!(state.playing ? connectionReady() : canStart())) return;
    if (state.scenario === "failed") { toast("Simulated command failure. Sample state unchanged."); return; }
    state.playing = !state.playing;
    if (state.position >= track().duration) state.position = 0;
    render(); toast(state.playing ? "Simulated playback · No audio output." : "Sample playback paused.");
  }
  document.addEventListener("click", (event) => {
    const button = event.target.closest("button");
    if (!button || button.disabled) return;
    if (button.dataset.view) {
      if (!["now", "night-clock"].includes(button.dataset.view) && !canBrowse()) return;
      state.view = button.dataset.view; state.compact = false; render(); window.scrollTo(0, 0);
      if (state.view === "night-clock") $("night-back").focus({ preventScroll: true });
    } else if (button.dataset.panel) { state.panel = button.dataset.panel; render(); }
    else if (button.dataset.track) selectTrack(button.dataset.track, button.dataset.context);
    else if (button.dataset.playlist) { state.detail = button.dataset.playlist; renderPlaylists(); $("playlist-detail").scrollIntoView({ block: "nearest" }); }
    else if (button.dataset.usePlaylist) {
      if (!canBrowse()) return;
      const list = playlists.find((item) => item.id === button.dataset.usePlaylist);
      state.queue = [...list.ids]; state.playlistId = list.id; state.trackId = list.ids[0];
      state.position = 0; state.playing = false; state.view = "now"; state.compact = false; state.scenario = "ready"; state.panel = "queue";
      render(); window.scrollTo(0, 0); toast(`Sample queue changed to ${list.name}. No audio output.`);
    } else if (button.dataset.album) { state.search = button.dataset.album; $("search").value = state.search; renderLibrary(); }
    else if (button.dataset.room) {
      const room = state.rooms.find((item) => item.id === button.dataset.room);
      if (!canRooms() || !room?.available) return;
      state.selectedRoom = room.id; state.rooms.forEach((item) => { item.grouped = item.id === room.id; });
      render(); toast(`Selected sample player: ${room.name}. No device contacted.`);
    } else if (button.dataset.group) {
      const room = state.rooms.find((item) => item.id === button.dataset.group);
      if (!canRooms() || !room?.available || !room.compatible || room.id === state.selectedRoom || (!room.grouped && state.scenario === "interrupted")) return;
      if (state.scenario === "failed") { toast("Simulated command failure. Sample group unchanged."); return; }
      room.grouped = !room.grouped; renderSpeakers(); toast(`Sample ${room.name} ${room.grouped ? "joined" : "left"} the group.`);
    } else if (button.hasAttribute("data-settings")) $("settings-dialog").showModal();
  });
  document.querySelector(".brand").addEventListener("click", (event) => { event.preventDefault(); state.view = "now"; state.compact = false; render(); });
  ["play", "compact-play"].forEach((id) => $(id).addEventListener("click", togglePlayback));
  ["next", "compact-next"].forEach((id) => $(id).addEventListener("click", () => skip(1)));
  ["previous", "compact-previous"].forEach((id) => $(id).addEventListener("click", () => skip(-1)));
  ["expand", "expand-track"].forEach((id) => $(id).addEventListener("click", () => { state.compact = false; state.view = "now"; render(); }));
  $("compact-toggle").addEventListener("click", () => { state.compact = !state.compact; state.view = "now"; render(); });
  $("output").addEventListener("click", () => { if (canRooms()) { state.panel = "speakers"; render(); $("speakers-tab").focus(); } });
  $("seek").addEventListener("input", () => { if (canSeek() && state.scenario !== "failed") { state.position = Number($("seek").value); renderProgress(); } });
  $("seek").addEventListener("change", () => {
    if (!canSeek()) return;
    if (state.scenario === "failed") { renderProgress(); toast("Simulated command failure. Sample position unchanged."); }
    else toast(`Sample position: ${time(state.position)}. No audio output.`);
  });
  document.addEventListener("input", (event) => {
    const input = event.target;
    if (!input.dataset.volume) return;
    const room = state.rooms.find((item) => item.id === input.dataset.volume);
    if (!canRooms() || !room?.available || !room.controllable) return;
    // During drag, display a draft; commit the fixture value on release.
    input.style.setProperty("--progress", `${input.value}%`);
    input.nextElementSibling.textContent = `${input.value}% preview`;
  });
  document.addEventListener("change", (event) => {
    const input = event.target;
    if (!input.dataset.volume) return;
    const room = state.rooms.find((item) => item.id === input.dataset.volume);
    if (!canRooms() || !room?.available || !room.controllable) return;
    if (state.scenario === "failed") { renderSpeakers(); toast("Simulated command failure. Sample volume unchanged."); return; }
    room.volume = Math.max(0, Math.min(100, Number(input.value))); renderSpeakers(); toast(`Sample ${room.name} volume: ${room.volume}%.`);
  });
  $("search").addEventListener("input", () => { state.search = $("search").value; renderLibrary(); });
  $("scenario").addEventListener("change", () => {
    state.scenario = $("scenario").value; state.playing = state.scenario === "interrupted"; state.compact = false; state.view = "now";
    render();
  });
  $("reset").addEventListener("click", () => { state = initialState(); $("search").value = ""; render(); toast("Sample player reset."); });
  $("dialog-close").addEventListener("click", () => $("settings-dialog").close());
  $("use-fixtures").addEventListener("click", () => { $("settings-dialog").close(); state = initialState(); $("search").value = ""; render(); });
  document.addEventListener("visibilitychange", manageTick);
  ["night-ink", "night-format"].forEach((id) => $(id).addEventListener("change", drawNightClock));
  $("night-presence").addEventListener("change", () => {
    $("night-fixture-note").textContent = `Sample presence: ${$("night-presence").value} · No Portal sensor or audio is connected`;
  });
  $("night-back").addEventListener("click", () => { state.view = "now"; render(); });
  window.addEventListener("resize", () => { if (state.view === "night-clock") drawNightClock(); });
  window.addEventListener("hashchange", () => {
    const view = location.hash.slice(1);
    if (["now", "library", "playlists", "night-clock"].includes(view)) { state.view = view; state.compact = false; render(); }
  });
  document.querySelector(".panel-tabs").addEventListener("keydown", (event) => {
    if (!["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) return;
    event.preventDefault(); state.panel = event.key === "Home" ? "queue" : event.key === "End" ? "speakers" : state.panel === "queue" ? "speakers" : "queue";
    render(); $(`${state.panel}-tab`).focus();
  });
  [["previous", "previous"], ["compact-previous", "previous"], ["next", "next"], ["compact-next", "next"], ["output-icon", "speaker"], ["output-chevron", "chevron"], ["queue-glyph", "queue"], ["speaker-glyph", "speaker"], ["search-icon", "search"], ["expand", "expand"], ["dialog-close", "close"]].forEach(([id, glyph]) => fill(id, glyph));
  const initialView = location.hash.slice(1);
  if (["now", "library", "playlists", "night-clock"].includes(initialView)) state.view = initialView;
  render();
})();
