/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
"use strict";

const statuses = [
  { id: "building", label: "Building", note: "Active verification" },
  { id: "verified", label: "Locally verified", note: "Local gates or isolated UI" },
  { id: "queued", label: "Queued", note: "Next bounded units" },
  { id: "approval", label: "Awaiting approval", note: "Decision before implementation" },
  { id: "delivered", label: "Delivered / implemented", note: "Evidence level shown per task" },
  { id: "parked", label: "Parked", note: "Original issue slots exhausted" }
];
const platformNames = {
  portal: "Portal", companion: "Android companion", browser: "Browser remote",
  tooling: "Fleet & delivery tooling", design: "Design mock"
};
const list = document.querySelector("#task-list");
const detail = document.querySelector("#task-detail");
const statusFilter = document.querySelector("#status-filter");
const platformFilter = document.querySelector("#platform-filter");
const resetButton = document.querySelector("#reset-filters");
let tasks = [];
let selectedId = null;

function element(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}
function badge(task) {
  const node = element("span", "status-badge", task.verification);
  node.dataset.status = task.status;
  return node;
}
function selectTask(id, moveToDetail = false) {
  const task = tasks.find(item => item.id === id);
  if (!task) return;
  selectedId = id;
  const url = new URL(location.href);
  url.hash = `task=${encodeURIComponent(id)}`;
  history.replaceState(null, "", url);
  renderList();
  renderDetail(task);
  if (moveToDetail && window.matchMedia("(max-width: 800px)").matches) {
    detail.scrollIntoView({ block: "start" });
    detail.focus({ preventScroll: true });
  }
}
function filteredTasks() {
  return tasks.filter(task =>
    (statusFilter.value === "all" || task.status === statusFilter.value) &&
    (platformFilter.value === "all" || task.platforms.includes(platformFilter.value))
  );
}
function renderList() {
  const visible = filteredTasks();
  list.replaceChildren();
  document.querySelector("#result-count").textContent = `${visible.length} of ${tasks.length} tasks`;
  resetButton.hidden = statusFilter.value === "all" && platformFilter.value === "all";
  if (!visible.length) {
    const empty = element("div", "empty", "No tasks match these filters.");
    const clear = element("button", "", "Show all tasks");
    clear.type = "button";
    clear.addEventListener("click", resetFilters);
    empty.append(clear);
    list.append(empty);
    return;
  }
  for (const status of statuses) {
    const group = visible.filter(task => task.status === status.id);
    if (!group.length) continue;
    const section = element("section");
    const heading = element("h3", "group-heading", `${status.label} (${group.length})`);
    heading.append(element("span", "group-note", status.note));
    section.append(heading);
    const rows = element("ul", "task-group");
    for (const task of group) {
      const li = element("li");
      const row = element("button", "task-row");
      row.type = "button";
      row.setAttribute("aria-current", task.id === selectedId ? "true" : "false");
      row.setAttribute("aria-controls", "task-detail");
      const content = element("span");
      content.append(element("span", "task-title", task.title));
      content.append(element("span", "task-summary", task.summary));
      const meta = element("span", "task-meta");
      meta.append(badge(task), element("span", "", task.platforms.map(p => platformNames[p]).join(" / ")));
      content.append(meta);
      const arrow = element("span", "row-arrow", "›");
      arrow.setAttribute("aria-hidden", "true");
      row.append(content, arrow);
      row.addEventListener("click", () => selectTask(task.id, true));
      li.append(row);
      rows.append(li);
    }
    section.append(rows);
    list.append(section);
  }
}
function listSection(title, items, className = "") {
  const section = element("section", className);
  section.append(element("h3", "", title));
  const ul = element("ul");
  items.forEach(text => ul.append(element("li", "", text)));
  section.append(ul);
  return section;
}
function renderDetail(task) {
  detail.replaceChildren();
  detail.scrollTop = 0;
  const heading = element("div", "detail-heading");
  heading.append(badge(task), element("span", "detail-type", task.kind));
  detail.append(heading, element("h2", "", task.title), element("p", "detail-summary", task.description));
  const facts = element("dl", "detail-facts");
  for (const [label, value] of [
    ["Platforms", task.platforms.map(p => platformNames[p]).join(", ")],
    ["Owner", task.owner],
    ["Artifact / deployment", task.deployment],
    ["Snapshot state", task.state]
  ]) {
    const fact = element("div");
    fact.append(element("dt", "", label), element("dd", "", value));
    facts.append(fact);
  }
  detail.append(facts, listSection("Acceptance", task.acceptance));
  const evidence = element("section");
  evidence.append(element("h3", "", "Evidence recorded"));
  task.evidence.forEach(item => {
    const block = element("div", "evidence-item");
    block.append(element("p", "", item.text));
    block.append(element("span", "source-ref", `Source: ${item.source}`));
    evidence.append(block);
  });
  detail.append(evidence, listSection("Limits & open checks", task.limits, "limits"));
  const next = element("p", "detail-next");
  next.append(element("strong", "", "Next action"), document.createTextNode(task.next));
  detail.append(next);
  const back = element("a", "back-to-queue", "Back to priority queue");
  back.href = "#roadmap";
  back.addEventListener("click", event => {
    event.preventDefault();
    document.querySelector("#roadmap").scrollIntoView({ block: "start" });
    list.querySelector('[aria-current="true"]')?.focus({ preventScroll: true });
  });
  detail.append(back);
}
function applyFilters() {
  const visible = filteredTasks();
  if (visible.length && !visible.some(task => task.id === selectedId)) {
    selectTask(visible[0].id);
  } else if (!visible.length) {
    renderList();
    detail.replaceChildren(element("p", "empty", "No matching task. Clear a filter to inspect the queue."));
  } else {
    renderList();
    renderDetail(visible.find(task => task.id === selectedId));
  }
}
function resetFilters() {
  statusFilter.value = "all";
  platformFilter.value = "all";
  applyFilters();
}
statusFilter.addEventListener("change", applyFilters);
platformFilter.addEventListener("change", applyFilters);
resetButton.addEventListener("click", resetFilters);
window.addEventListener("hashchange", () => {
  const id = new URLSearchParams(location.hash.slice(1)).get("task");
  if (!tasks.some(task => task.id === id)) return;
  statusFilter.value = "all";
  platformFilter.value = "all";
  selectTask(id);
});
document.querySelector("#clock-detail").addEventListener("click", () => {
  statusFilter.value = "all";
  platformFilter.value = "all";
  selectTask("world-clock");
  detail.scrollIntoView({ block: "start" });
  detail.focus({ preventScroll: true });
});
document.querySelectorAll("[data-image]").forEach(button => {
  button.addEventListener("click", () => {
    const large = button.dataset.image === "large";
    const image = document.querySelector("#after-image");
    image.src = large ? "images/final-three-large-font-cold-card.png" : "images/final-three-normal-card.png";
    image.alt = large
      ? "Verified World Clock after a cold launch at 150% text size: one complete city row and an unclipped 2 more cities caption."
      : "Verified World Clock card with complete New York and London rows and a 1 more city caption.";
    document.querySelector("#after-caption").textContent = large ? "150% text · cold launch" : "Normal text · 3 cities";
    document.querySelectorAll("[data-image]").forEach(item => item.setAttribute("aria-pressed", String(item === button)));
  });
});
async function init() {
  try {
    const response = await fetch("roadmap.json", { cache: "no-store" });
    if (!response.ok) throw new Error(`Snapshot HTTP ${response.status}`);
    const data = await response.json();
    if (!Array.isArray(data.tasks) || !data.tasks.length) throw new Error("Snapshot has no task records");
    tasks = data.tasks;
    document.querySelector("#snapshot-date").textContent = `Last updated: ${data.updatedLabel}`;
    statuses.forEach(status => {
      const option = element("option", "", status.label);
      option.value = status.id;
      statusFilter.append(option);
    });
    Object.entries(platformNames).forEach(([id, name]) => {
      const option = element("option", "", name);
      option.value = id;
      platformFilter.append(option);
    });
    const hashId = new URLSearchParams(location.hash.slice(1)).get("task");
    selectTask(tasks.some(task => task.id === hashId) ? hashId : data.defaultTask);
  } catch (error) {
    list.replaceChildren(element("p", "empty", "The saved roadmap could not be loaded. Refresh this local page or open roadmap.json to inspect the snapshot."));
    detail.replaceChildren(element("p", "empty", "No task data loaded. No verification status has been inferred."));
    document.querySelector("#clock-detail").disabled = true;
    statusFilter.disabled = true;
    platformFilter.disabled = true;
    console.error("Roadmap snapshot load failed:", error);
  }
}
init();
