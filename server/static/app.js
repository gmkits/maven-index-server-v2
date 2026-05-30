const form = document.querySelector("#searchForm");
const input = document.querySelector("#searchInput");
const statusLine = document.querySelector("#statusLine");
const resultsList = document.querySelector("#resultsList");
const detailsPanel = document.querySelector("#detailsPanel");
const healthText = document.querySelector("#healthText");

let activeKey = "";

function text(value, fallback = "") {
  return value === null || value === undefined || value === "" ? fallback : String(value);
}

function html(value) {
  return text(value)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

function setStatus(message) {
  statusLine.textContent = message;
}

function escapeCoord(value) {
  return String(value).replace(/'/g, "\\'");
}

function versionFor(result) {
  return result.latestStableVersion || result.latestVersion || "";
}

function dependencyText(result, format) {
  const version = versionFor(result);
  if (format === "maven") {
    return `<dependency>
  <groupId>${result.groupId}</groupId>
  <artifactId>${result.artifactId}</artifactId>
  <version>${version}</version>
</dependency>`;
  }
  return `implementation '${escapeCoord(result.groupId)}:${escapeCoord(result.artifactId)}:${escapeCoord(version)}'`;
}

async function copyText(value, button) {
  try {
    await navigator.clipboard.writeText(value);
    const previous = button.textContent;
    button.textContent = "Copied";
    setTimeout(() => {
      button.textContent = previous;
    }, 1200);
  } catch {
    setStatus("Clipboard is unavailable in this browser.");
  }
}

function renderEmpty(message = "Select an artifact", detail = "Versions and dependency coordinates appear here.") {
  detailsPanel.innerHTML = `
    <div class="empty-state">
      <div class="empty-title">${html(message)}</div>
      <div class="empty-copy">${html(detail)}</div>
    </div>
  `;
}

function renderError(title, detail) {
  detailsPanel.innerHTML = `
    <div class="error-state">
      <div class="error-title">${html(title)}</div>
      <div>${html(detail)}</div>
    </div>
  `;
}

function renderResults(items) {
  resultsList.innerHTML = "";
  if (!items.length) {
    resultsList.innerHTML = `
      <div class="result-item">
        <div class="result-title">
          <span class="artifact">No artifacts found</span>
        </div>
        <div class="meta-row">
          <span class="badge">Try another query</span>
        </div>
      </div>
    `;
    renderEmpty("No selection", "Search results will appear on the left.");
    return;
  }

  for (const item of items) {
    const key = `${item.groupId}:${item.artifactId}`;
    const button = document.createElement("button");
    button.type = "button";
    button.className = `result-item${key === activeKey ? " active" : ""}`;
    button.innerHTML = `
      <div class="result-title">
        <span class="artifact">${html(item.artifactId)}</span>
        <span class="group">${html(item.groupId)}</span>
      </div>
      <div class="meta-row">
        <span class="badge latest">latest ${html(text(item.latestVersion, "n/a"))}</span>
        <span class="badge stable">stable ${html(text(item.latestStableVersion, "n/a"))}</span>
        <span class="badge">${html(item.versionCount)} versions</span>
      </div>
    `;
    button.addEventListener("click", () => selectArtifact(item));
    resultsList.append(button);
  }
}

function setActiveResult(key) {
  activeKey = key;
  for (const node of resultsList.querySelectorAll(".result-item")) {
    const title = node.querySelector(".group");
    const artifact = node.querySelector(".artifact");
    const nodeKey = title && artifact ? `${title.textContent}:${artifact.textContent}` : "";
    node.classList.toggle("active", nodeKey === key);
  }
}

async function selectArtifact(result) {
  const key = `${result.groupId}:${result.artifactId}`;
  setActiveResult(key);
  detailsPanel.innerHTML = `
    <div class="empty-state">
      <div class="empty-title">Loading versions</div>
      <div>${html(key)}</div>
    </div>
  `;

  try {
    const params = new URLSearchParams({ groupId: result.groupId, artifactId: result.artifactId });
    const response = await fetch(`/api/maven/versions?${params.toString()}`);
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`);
    }
    const data = await response.json();
    renderDetails({ ...result, ...data });
  } catch (error) {
    renderError("Versions unavailable", error.message);
  }
}

function renderDetails(result) {
  const version = versionFor(result);
  const mavenText = dependencyText(result, "maven");
  const gradleText = dependencyText(result, "gradle");
  const versions = Array.isArray(result.versions) ? result.versions : [];
  const visibleVersions = versions.slice(0, 80);

  detailsPanel.innerHTML = `
    <h2 class="details-title">${html(result.artifactId)}</h2>
    <div class="details-subtitle">${html(result.groupId)}</div>
    <div class="meta-row">
      <span class="badge latest">latest ${html(text(result.latestVersion, "n/a"))}</span>
      <span class="badge stable">stable ${html(text(result.latestStableVersion, "n/a"))}</span>
      <span class="badge">${html(result.versionCount || versions.length)} versions</span>
    </div>
    <div class="copy-stack">
      <div class="coord-block">
        <label>Maven</label>
        <div class="coord-row">
          <code id="mavenCoord"></code>
          <button class="copy-button" type="button" id="copyMaven">Copy</button>
        </div>
      </div>
      <div class="coord-block">
        <label>Gradle</label>
        <div class="coord-row">
          <code id="gradleCoord"></code>
          <button class="copy-button" type="button" id="copyGradle">Copy</button>
        </div>
      </div>
    </div>
    <div class="version-list" aria-label="Versions">
      ${visibleVersions.map((item) => `
        <div class="version-row">
          <code>${html(item.v)}</code>
          <span>${item.stable ? "stable" : html(text(item.p, ""))}</span>
        </div>
      `).join("")}
      ${versions.length > visibleVersions.length ? `<div class="version-row"><code>${html(versions.length - visibleVersions.length)} more</code><span></span></div>` : ""}
    </div>
  `;

  detailsPanel.querySelector("#mavenCoord").textContent = mavenText;
  detailsPanel.querySelector("#gradleCoord").textContent = gradleText;
  detailsPanel.querySelector("#copyMaven").addEventListener("click", (event) => copyText(mavenText, event.currentTarget));
  detailsPanel.querySelector("#copyGradle").addEventListener("click", (event) => copyText(gradleText, event.currentTarget));

  if (!version) {
    setStatus("Selected artifact has no latest version in this index.");
  }
}

async function search(query) {
  const trimmed = query.trim();
  if (!trimmed) {
    setStatus("");
    resultsList.innerHTML = "";
    renderEmpty();
    return;
  }

  setStatus(`Searching "${trimmed}"`);
  resultsList.innerHTML = "";
  renderEmpty("Search in progress", trimmed);

  try {
    const params = new URLSearchParams({ q: trimmed, limit: "20" });
    const response = await fetch(`/api/maven/search?${params.toString()}`);
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`);
    }
    const data = await response.json();
    const results = Array.isArray(data.results) ? data.results : [];
    activeKey = "";
    renderResults(results);
    setStatus(`${results.length} result${results.length === 1 ? "" : "s"} for "${trimmed}"`);
  } catch (error) {
    resultsList.innerHTML = "";
    setStatus("Search failed.");
    renderError("Search unavailable", error.message);
  }
}

async function loadHealth() {
  try {
    const response = await fetch("/health");
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`);
    }
    const data = await response.json();
    healthText.textContent = `${Number(data.artifacts || 0).toLocaleString()} artifacts indexed`;
  } catch {
    healthText.textContent = "Index status unavailable";
  }
}

form.addEventListener("submit", (event) => {
  event.preventDefault();
  const query = input.value.trim();
  const url = new URL(window.location.href);
  if (query) {
    url.searchParams.set("q", query);
  } else {
    url.searchParams.delete("q");
  }
  window.history.replaceState({}, "", url.toString());
  search(query);
});

const initialQuery = new URLSearchParams(window.location.search).get("q") || "";
input.value = initialQuery;
loadHealth();
search(initialQuery);
input.focus();
