const form = document.querySelector("#searchForm");
const input = document.querySelector("#searchInput");
const clearSearch = document.querySelector("#clearSearch");
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

function formatDate(value) {
  if (!value) {
    return "-";
  }
  const date = new Date(Number(value));
  if (Number.isNaN(date.getTime())) {
    return "-";
  }
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

async function copyText(value, button) {
  try {
    await navigator.clipboard.writeText(value);
    const previous = button.textContent;
    button.textContent = "已复制";
    setTimeout(() => {
      button.textContent = previous;
    }, 1200);
  } catch {
    setStatus("当前浏览器不可用剪贴板。");
  }
}

function renderEmpty(message = "选择一个包", detail = "这里会显示版本和依赖坐标。") {
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

function packageIcon() {
  return '<span class="package-icon" aria-hidden="true"><span></span></span>';
}

function renderResults(items) {
  resultsList.innerHTML = "";
  if (!items.length) {
    resultsList.innerHTML = `
      <div class="result-item empty-result">
        <div class="result-title">
          ${packageIcon()}
          <div>
            <span class="artifact">没有找到匹配的包</span>
            <span class="group">换个关键词试试</span>
          </div>
        </div>
        <div class="result-cell latest">-</div>
        <div class="result-cell stable">-</div>
        <div class="result-cell count">-</div>
      </div>
    `;
    renderEmpty("没有结果", "搜索结果会显示在左侧。");
    return;
  }

  for (const item of items) {
    const key = `${item.groupId}:${item.artifactId}`;
    const button = document.createElement("button");
    button.type = "button";
    button.className = `result-item${key === activeKey ? " active" : ""}`;
    button.innerHTML = `
      <div class="result-title">
        ${packageIcon()}
        <div>
          <span class="artifact">${html(item.artifactId)}</span>
          <span class="group">${html(item.groupId)}</span>
        </div>
      </div>
      <div class="result-cell latest">${html(text(item.latestVersion, "-"))}</div>
      <div class="result-cell stable">${html(text(item.latestStableVersion, "-"))}</div>
      <div class="result-cell count">${html(item.versionCount)}</div>
    `;
    button.addEventListener("click", () => selectArtifact(item));
    resultsList.append(button);
  }
}

function renderResultFooter(count) {
  const footer = document.createElement("div");
  footer.className = "result-footer";
  footer.innerHTML = `
    <button type="button" disabled aria-label="上一页">‹</button>
    <button type="button" class="active-page">1</button>
    <button type="button" disabled aria-label="下一页">›</button>
    <span>每页 ${count} 条</span>
  `;
  resultsList.append(footer);
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
      <div class="empty-title">正在加载版本</div>
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
    renderError("版本不可用", error.message);
  }
}

function renderDetails(result) {
  const version = versionFor(result);
  const mavenText = dependencyText(result, "maven");
  const gradleText = dependencyText(result, "gradle");
  const versions = Array.isArray(result.versions) ? result.versions : [];

  detailsPanel.innerHTML = `
    <div class="details-head">
      <div class="details-icon">${packageIcon()}</div>
      <div class="details-main">
        <div class="details-title-row">
          <h2 class="details-title">${html(result.artifactId)}</h2>
          <button class="icon-copy" id="copyGA" type="button" aria-label="复制包坐标"></button>
        </div>
        <div class="details-subtitle">${html(result.groupId)}</div>
      </div>
    </div>
    <div class="meta-row">
      <span class="badge latest">最新 ${html(text(result.latestVersion, "-"))}</span>
      <span class="badge stable">稳定 ${html(text(result.latestStableVersion, "-"))}</span>
      <span class="badge">${html(result.versionCount || versions.length)} 个版本</span>
    </div>
    <div class="detail-tabs" role="tablist" aria-label="详情分类">
      <button class="active" type="button" data-tab="dependency">依赖引入</button>
      <button type="button" data-tab="versions">版本历史</button>
    </div>
    <section class="tab-panel active" data-panel="dependency">
      <div class="coord-block">
        <label><span class="tool-mark">M</span> Maven</label>
        <div class="coord-row">
          <code id="mavenCoord"></code>
          <button class="copy-button" type="button" id="copyMaven">复制</button>
        </div>
      </div>
      <div class="coord-block">
        <label><span class="gradle-mark"></span> Gradle</label>
        <div class="coord-row">
          <code id="gradleCoord"></code>
          <button class="copy-button" type="button" id="copyGradle">复制</button>
        </div>
      </div>
    </section>
    <section class="tab-panel" data-panel="versions">
      ${versionHistoryHTML(versions)}
    </section>
  `;

  detailsPanel.querySelector("#mavenCoord").textContent = mavenText;
  detailsPanel.querySelector("#gradleCoord").textContent = gradleText;
  detailsPanel.querySelector("#copyGA").addEventListener("click", (event) => copyText(`${result.groupId}:${result.artifactId}`, event.currentTarget));
  detailsPanel.querySelector("#copyMaven").addEventListener("click", (event) => copyText(mavenText, event.currentTarget));
  detailsPanel.querySelector("#copyGradle").addEventListener("click", (event) => copyText(gradleText, event.currentTarget));
  for (const tab of detailsPanel.querySelectorAll("[data-tab]")) {
    tab.addEventListener("click", () => activateTab(tab.dataset.tab));
  }
  const moreVersions = detailsPanel.querySelector(".more-versions");
  if (moreVersions) {
    moreVersions.addEventListener("click", () => {
      detailsPanel.querySelector('[data-panel="versions"]').innerHTML = versionHistoryHTML(versions, true);
    });
  }

  if (!version) {
    setStatus("这个包在当前索引里没有最新版本字段。");
  }
}

function versionHistoryHTML(versions, expanded = false) {
  const visibleVersions = expanded ? versions : versions.slice(0, 10);
  return `
    <div class="version-head">
      <span>版本历史</span>
      <span>发布时间</span>
    </div>
    <div class="version-list" aria-label="版本列表">
      ${visibleVersions.map((item) => `
        <div class="version-row">
          <span class="version-name"><span class="shield" aria-hidden="true"></span>${html(item.v)}</span>
          <span class="version-date">${html(formatDate(item.t))}</span>
          <span class="version-stable">${item.stable ? "稳定版" : html(text(item.p, ""))}</span>
        </div>
      `).join("")}
    </div>
    ${versions.length > visibleVersions.length ? `<button class="more-versions" type="button">查看更多版本</button>` : ""}
  `;
}

function activateTab(name) {
  for (const tab of detailsPanel.querySelectorAll("[data-tab]")) {
    tab.classList.toggle("active", tab.dataset.tab === name);
  }
  for (const panel of detailsPanel.querySelectorAll("[data-panel]")) {
    panel.classList.toggle("active", panel.dataset.panel === name);
  }
}

async function search(query) {
  const trimmed = query.trim();
  clearSearch.hidden = trimmed.length === 0;
  if (!trimmed) {
    setStatus("");
    resultsList.innerHTML = "";
    renderEmpty();
    return;
  }

  setStatus(`正在搜索“${trimmed}”`);
  resultsList.innerHTML = "";
  renderEmpty("正在搜索", trimmed);

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
    if (results.length > 0) {
      renderResultFooter(results.length);
      selectArtifact(results[0]);
    }
    setStatus(`“${trimmed}”找到 ${results.length} 个结果`);
  } catch (error) {
    resultsList.innerHTML = "";
    setStatus("搜索失败。");
    renderError("搜索不可用", error.message);
  }
}

async function loadHealth() {
  try {
    const response = await fetch("/health");
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`);
    }
    const data = await response.json();
    healthText.innerHTML = `已索引 <strong>${Number(data.artifacts || 0).toLocaleString()}</strong> 个 Maven 包`;
  } catch {
    healthText.textContent = "索引状态不可用";
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

clearSearch.addEventListener("click", () => {
  input.value = "";
  input.focus();
  const url = new URL(window.location.href);
  url.searchParams.delete("q");
  window.history.replaceState({}, "", url.toString());
  search("");
});

input.addEventListener("input", () => {
  clearSearch.hidden = input.value.trim().length === 0;
});

const initialQuery = new URLSearchParams(window.location.search).get("q") || "";
input.value = initialQuery;
clearSearch.hidden = initialQuery.trim().length === 0;
loadHealth();
search(initialQuery);
input.focus();
