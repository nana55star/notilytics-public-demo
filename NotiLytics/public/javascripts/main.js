document.addEventListener("DOMContentLoaded", () => {
  initSearchPage();
  initSourceDirectory();
});

function getDisplayPublished(article) {
  if (article.publishedEdt && article.publishedEdt.trim()) return article.publishedEdt; // no suffix
  if (article.publishedAt) return formatRelativeDate(article.publishedAt);
  return "";
}

function getSentimentClass(sentiment) {
  if (!sentiment) return "";
  const s = sentiment.trim();
  if (s === ":-)") return "sentiment-happy";
  if (s === ":-(") return "sentiment-sad";
  if (s === ":-|") return "sentiment-neutral";
  return "";
}

function initSearchPage() {
  const form = document.getElementById("search-form");
  if (!form) return;

  const input = document.getElementById("q");
  const resultsDiv = document.getElementById("results");
  const sourcesSelect = document.getElementById("sources");
  const submitBtn = document.getElementById("search-btn");
  const clearSourcesBtn = document.getElementById("clear-sources-btn");
  const clearResultsBtn = document.getElementById("clear-results-btn");
  const countrySelect = document.getElementById("country");
  const categorySelect = document.getElementById("category");
  const languageSelect = document.getElementById("language");
  const sortSelect = document.getElementById("sortBy");
  const overallDiv = document.getElementById("overall-sentiment");
  let activeSocket = null;
  let activeSessionId = null;
  const liveSessions = new Map();

  // Country -> languages mapping for "All Languages" union
  const COUNTRY_LANG_MAP = {
    us: ["en", "es"],
    ca: ["en", "fr"],
    gb: ["en"],
    fr: ["fr"],
    de: ["de"],
    es: ["es"],
    sa: ["ar"],
    eg: ["ar"]
  };

  const debounce = (fn, ms = 250) => {
    let t;
    return (...args) => { clearTimeout(t); t = setTimeout(() => fn(...args), ms); };
  };

  // Get selected sort option
  const getSortBy = () => (sortSelect?.value || "relevancy").trim();

  // Apply language constraints based on country selection
  function applyLanguageConstraint() {
    const country = countrySelect?.value || "";
    const valid = COUNTRY_LANG_MAP[country] || [];

    Array.from(languageSelect.options).forEach(opt => {
      if (opt.value === "") { opt.hidden = false; opt.disabled = false; return; }
      const allow = (valid.length === 0) || valid.includes(opt.value);
      opt.hidden = !allow;
      opt.disabled = !allow;
    });

    if (languageSelect.value && valid.length > 0 && !valid.includes(languageSelect.value)) {
      languageSelect.value = "";
    }
  }

  // Fetch sources with specific filters
  async function fetchSourcesOnce({ country, category, language }) {
    const params = new URLSearchParams();
    if (country) params.append("country", country);
    if (category) params.append("category", category);
    if (language) params.append("language", language);

    const url = params.toString() ? `/sources?${params.toString()}` : `/sources`;
    const res = await fetch(url);

    if (res.status === 429) {
      return { rateLimited: true, sources: [] };
    }
    if (!res.ok) throw new Error(`HTTP ${res.status}`);

    const data = await res.json();
    if (data.status === "error" || data.error) {
      throw new Error(data.message || data.error || "API error");
    }
    return { rateLimited: false, sources: data.sources || [] };
  }

  // Merge sources by ID
  function mergeSources(arrays) {
    const byId = new Map();
    arrays.flat().forEach(s => {
      if (!byId.has(s.id)) byId.set(s.id, s);
    });
    return Array.from(byId.values());
  }

  // Load sources based on current filter selections
  async function loadSources() {
    try {
      if (sourcesSelect) {
        sourcesSelect.innerHTML = `<option disabled>Loading sources…</option>`;
        sourcesSelect.selectedIndex = -1;
      }

      const country = countrySelect?.value || "";
      const category = categorySelect?.value || "";
      const language = languageSelect?.value || "";

      // If "All Languages" and country has multiple languages, fetch and merge
      if (!language && country && COUNTRY_LANG_MAP[country]?.length) {
        const langs = COUNTRY_LANG_MAP[country];
        const results = await Promise.all(
          langs.map(l => fetchSourcesOnce({ country, category, language: l }))
        );

        if (results.some(r => r.rateLimited)) {
          sourcesSelect.innerHTML = `<option disabled>Rate limit reached — try again soon</option>`;
          return;
        }

        const merged = mergeSources(results.map(r => r.sources))
          .sort((a, b) => a.name.localeCompare(b.name));

        sourcesSelect.innerHTML = merged.length
          ? merged.map(s => `<option value="${s.id}">${escapeHtml(s.name)}</option>`).join("")
          : `<option disabled>(no sources)</option>`;
        sourcesSelect.selectedIndex = -1;
        return;
      }

      // Single call for explicit language or no country map
      const single = await fetchSourcesOnce({ country, category, language });
      if (single.rateLimited) {
        sourcesSelect.innerHTML = `<option disabled>Rate limit reached — try again soon</option>`;
        return;
      }

      const opts = single.sources
        .sort((a, b) => a.name.localeCompare(b.name))
        .map(s => `<option value="${s.id}">${escapeHtml(s.name)}</option>`)
        .join("");

      sourcesSelect.innerHTML = opts || `<option disabled>(no sources)</option>`;
      sourcesSelect.selectedIndex = -1;
    } catch (e) {
      console.warn("Failed to load sources:", e);
      sourcesSelect.innerHTML = `<option disabled>Failed to load sources</option>`;
      sourcesSelect.selectedIndex = -1;
    }
  }

  // Initial setup
  applyLanguageConstraint();
  loadSources();

  const loadSourcesDebounced = debounce(loadSources, 250);

  // Clear sources button
  clearSourcesBtn?.addEventListener("click", () => {
    if (sourcesSelect) {
      Array.from(sourcesSelect.options).forEach(opt => opt.selected = false);
      sourcesSelect.selectedIndex = -1;
    }
  });

  // Clear all results button
  clearResultsBtn?.addEventListener("click", () => {
    closeActiveStream();
    liveSessions.clear();
    resultsDiv.innerHTML = "";
    if (overallDiv) overallDiv.innerHTML = "";
    clearResultsBtn.style.display = "none";
  });

  // Event listeners for filter changes
  countrySelect?.addEventListener("change", () => {
    applyLanguageConstraint();
    loadSourcesDebounced();
  });
  categorySelect?.addEventListener("change", loadSourcesDebounced);
  languageSelect?.addEventListener("change", loadSourcesDebounced);

  // Search form submission
  form.addEventListener("submit", (event) => {
    event.preventDefault();

    const query = input.value.trim();
    const selectedSources = Array.from(sourcesSelect.selectedOptions)
      .map((option) => option.value)
      .filter(Boolean);
    const country = countrySelect?.value || "";
    const category = categorySelect?.value || "";
    const language = languageSelect?.value || "";
    const sortBy = getSortBy();

    if (!query && selectedSources.length === 0 && !country && !category && !language) {
      resultsDiv.textContent = "Please enter a query or pick at least one filter.";
      return;
    }

    const searchParams = { query, sources: selectedSources, country, category, language, sortBy };
    resultsDiv.setAttribute("aria-busy", "true");
    submitBtn.disabled = true;

    closeActiveStream();

    const sessionId = generateSessionId();
    const sessionElements = createLiveResultSet(searchParams, sessionId);
    liveSessions.set(sessionId, { ...sessionElements, params: searchParams, articleCount: 0 });
    resultsDiv.prepend(sessionElements.container);
    clearResultsBtn.style.display = "inline-block";
    if (overallDiv) overallDiv.innerHTML = "";

    const params = new URLSearchParams();
    if (query) params.set("q", query);
    for (const s of selectedSources) params.append("sources", s);
    if (country) params.set("country", country);
    if (category) params.set("category", category);
    if (language) params.set("language", language);
    if (sortBy) params.set("sortBy", sortBy);
    params.set("sessionId", sessionId);

    const wsProtocol = window.location.protocol === "https:" ? "wss:" : "ws:";
    const wsUrl = `${wsProtocol}//${window.location.host}/ws/stream?${params.toString()}`;
    const socket = new WebSocket(wsUrl);
    activeSocket = socket;
    activeSessionId = sessionId;
    sessionElements.status.textContent = "Fetching the latest articles…";

    socket.onopen = () => {
      if (activeSocket !== socket) return;
      sessionElements.status.textContent = "Listening for updates…";
    };

    socket.onmessage = (message) => {
      handleSocketMessage(message.data);
    };

    socket.onerror = () => {
      handleConnectionError(sessionId);
      if (activeSocket === socket) {
        closeActiveStream();
      }
    };

    socket.onclose = () => {
      if (activeSocket === socket) {
        closeActiveStream();
      }
    };

    submitBtn.disabled = false;
  });

  function handleInitEvent(sessionId, evt) {
    const payload = parseEventPayload(evt);
    if (!payload) return;

    const session = liveSessions.get(sessionId);
    if (!session) return;

    const articles = Array.isArray(payload.articles) ? payload.articles : [];
    session.articleCount = articles.length;
    session.articlesContainer.innerHTML = articles.length
      ? articles.map((article) => renderArticleCard(article, session.params.query, session.params.language)).join("")
      : `<p class="text-muted">No results yet. Waiting for new submissions…</p>`;
    session.summary.innerHTML = buildSearchSummary(session.params, session.articleCount, payload.overallSentiment || "");
    session.status.textContent = articles.length
      ? "Showing the latest articles. Listening for updates…"
      : "Listening for new articles…";

    resultsDiv.setAttribute("aria-busy", "false");

    if (sessionId === activeSessionId) {
      updateOverallSentiment(payload.overallSentiment || "");
    }
  }

  function handleAppendEvent(sessionId, evt) {
    const payload = parseEventPayload(evt);
    if (!payload) return;
    const session = liveSessions.get(sessionId);
    if (!session) return;

    const articles = Array.isArray(payload.articles) ? payload.articles : [];
    if (!articles.length) return;

    const html = articles.map((article) => renderArticleCard(article, session.params.query, session.params.language)).join("");
    session.articlesContainer.insertAdjacentHTML("afterbegin", html);
    session.articleCount = (session.articleCount || 0) + articles.length;
    session.summary.innerHTML = buildSearchSummary(session.params, session.articleCount, payload.overallSentiment || "");
    session.status.textContent = `Updated ${new Date().toLocaleTimeString()}`;

    if (sessionId === activeSessionId) {
      updateOverallSentiment(payload.overallSentiment || "");
    }
  }

  function handleServerStreamError(sessionId, evt) {
    const payload = parseEventPayload(evt) || {};
    const session = liveSessions.get(sessionId);
    if (session) {
      session.status.textContent = `⚠️ ${payload.message || "Live stream closed"}`;
    }
    resultsDiv.setAttribute("aria-busy", "false");
  }

  function handleHistoryEvent(sessionId, evt) {
    const payload = parseEventPayload(evt);
    if (!payload || !Array.isArray(payload.history)) return;
    const session = liveSessions.get(sessionId);
    if (!session || !session.history) return;

    if (payload.history.length === 0) {
      session.history.textContent = "No search history yet.";
      return;
    }

    const chips = payload.history.map((entry) => formatHistoryEntry(entry)).filter(Boolean);
    if (!chips.length) {
      session.history.textContent = "No search history yet.";
    } else {
      session.history.innerHTML = `<strong>History:</strong> ${chips.join(" ")}`;
    }
  }

  function handleConnectionError(sessionId) {
    const session = liveSessions.get(sessionId);
    if (session) {
      session.status.textContent = "⚠️ Lost connection to live updates.";
    }
    resultsDiv.setAttribute("aria-busy", "false");
  }

  function parseEventPayload(evt) {
    try {
      return JSON.parse(evt.data || "{}");
    } catch (error) {
      console.warn("Failed to parse streaming payload", error);
      return null;
    }
  }

  function parseSseMessage(raw) {
    if (!raw) return null;
    let eventName = null;
    let dataLine = null;
    raw.split(/\n/).forEach(line => {
      if (line.startsWith("event:")) {
        eventName = line.replace("event:", "").trim();
      } else if (line.startsWith("data:")) {
        dataLine = line.replace("data:", "").trim();
      }
    });
    if (!eventName) return null;
    try {
      const parsedData = dataLine ? JSON.parse(dataLine) : {};
      return { eventName, data: parsedData };
    } catch (err) {
      console.warn("Failed to parse WS message", err);
      return null;
    }
  }

  function handleSocketMessage(raw) {
    const parsed = parseSseMessage(raw);
    if (!parsed) return;
    const sessionId = parsed.data?.sessionId || activeSessionId;
    const evtLike = { data: JSON.stringify(parsed.data || {}) };
    if (parsed.eventName === "init") {
      handleInitEvent(sessionId, evtLike);
    } else if (parsed.eventName === "append") {
      handleAppendEvent(sessionId, evtLike);
    } else if (parsed.eventName === "stream-error") {
      handleServerStreamError(sessionId, evtLike);
      if (activeSessionId === sessionId) {
        closeActiveStream();
      }
    } else if (parsed.eventName === "history") {
      handleHistoryEvent(sessionId, evtLike);
    }
  }

  function createLiveResultSet(searchParams, sessionId) {
    const container = document.createElement("div");
    container.className = "search-result-set mb-4 pb-4 border-bottom";
    container.dataset.sessionId = sessionId;

    const header = document.createElement("div");
    header.className = "d-flex justify-content-between align-items-center mb-2";
    const title = searchParams.query
      ? `Live results for "${escapeHtml(searchParams.query)}"`
      : "Live results";
    header.innerHTML = `<h4 class="mb-0">${title}</h4><span class="badge bg-success">LIVE</span>`;
    container.appendChild(header);

    const summary = document.createElement("div");
    summary.className = "alert alert-info mb-3";
    summary.innerHTML = buildSearchSummary(searchParams, 0, "");
    container.appendChild(summary);

    const status = document.createElement("div");
    status.className = "text-muted small mb-2";
    status.textContent = "Connecting…";
    container.appendChild(status);

    const history = document.createElement("div");
    history.className = "session-history small text-muted mb-2";
    history.textContent = "No search history yet.";
    container.appendChild(history);

    const articlesContainer = document.createElement("div");
    container.appendChild(articlesContainer);

    return { container, summary, status, history, articlesContainer };
  }

  function buildSearchSummary(searchParams, resultCount, sentiment) {
    const summaryParts = [];
    if (searchParams.query) {
      summaryParts.push(`<strong>Search details:</strong>`);
    } else if (searchParams.sources.length > 0) {
      summaryParts.push(`<strong>Search results from selected sources</strong>`);
    } else {
      summaryParts.push(`<strong>Search results</strong>`);
    }

    const filters = [];
    if (searchParams.sources.length > 0) {
      filters.push(`Sources: ${searchParams.sources.map((s) => escapeHtml(s)).join(", ")}`);
    }
    if (searchParams.category) {
      const categoryName = searchParams.category.charAt(0).toUpperCase() + searchParams.category.slice(1);
      filters.push(`Category: ${categoryName}`);
    }
    if (searchParams.country) {
      filters.push(`Country: ${searchParams.country.toUpperCase()}`);
    }
    if (searchParams.language) {
      filters.push(`Language: ${searchParams.language.toUpperCase()}`);
    }

    const sortByText = searchParams.sortBy === "publishedAt" ? "Published Date"
      : searchParams.sortBy === "popularity" ? "Popularity" : "Relevancy";
    filters.push(`Sorted by: ${sortByText}`);

    if (filters.length > 0) {
      summaryParts.push(`<span class="text-muted">(${filters.join(" | ")})</span>`);
    }

    if (sentiment) {
      summaryParts.push(`<span class="sentiment-badge ${getSentimentClass(sentiment)} ms-2">${escapeHtml(sentiment)}</span>`);
    }

    summaryParts.push(`<span class="badge bg-primary">${resultCount} result${resultCount === 1 ? "" : "s"}</span>`);
    return summaryParts.join(" ");
  }

  function formatHistoryEntry(entry) {
    if (!entry) return "";
    const tokens = [];
    if (entry.query) {
      tokens.push(`"${escapeHtml(entry.query)}"`);
    } else if (entry.sources) {
      tokens.push(`Sources: ${escapeHtml(entry.sources)}`);
    } else {
      tokens.push("Filters");
    }

    if (entry.language) {
      tokens.push(`Lang ${escapeHtml(entry.language.toUpperCase())}`);
    }
    if (entry.country) {
      tokens.push(`Country ${escapeHtml(entry.country.toUpperCase())}`);
    }
    if (entry.sortBy) {
      tokens.push(`Sort ${escapeHtml(entry.sortBy)}`);
    }

    if (entry.timestamp) {
      const time = new Date(entry.timestamp);
      if (!Number.isNaN(time.getTime())) {
        tokens.push(time.toLocaleTimeString());
      }
    }

    if (!tokens.length) return "";
    return `<span class="badge bg-light text-dark text-wrap">${tokens.join(" · ")}</span>`;
  }

  function updateOverallSentiment(sentiment) {
    if (!overallDiv) return;
    if (!sentiment) {
      overallDiv.innerHTML = "";
      return;
    }

    overallDiv.innerHTML = `<div class="overall-sentiment-container" role="status">
      <span class="sentiment-label">Overall Sentiment:</span>
      <span class="sentiment-badge ${getSentimentClass(sentiment)}">${escapeHtml(sentiment)}</span>
    </div>`;
  }

  function generateSessionId() {
    if (window.crypto?.randomUUID) {
      return window.crypto.randomUUID();
    }
    return `session-${Date.now()}-${Math.random().toString(16).slice(2)}`;
  }

  function closeActiveStream() {
    if (activeSocket) {
      activeSocket.close();
      activeSocket = null;
    }
    activeSessionId = null;
    resultsDiv.setAttribute("aria-busy", "false");
  }
}

function renderArticleCard(article, query, language) {
  const title = highlightText(article.title || "(no title)", query);
  const description = article.description ? escapeHtml(article.description) : "";

  // Support both old format (source.name) and new format (sourceName)
  const sourceName = article.sourceName || (article.source && article.source.name) || "Unknown source";
  const sourceId = article.sourceId || (article.source && article.source.id) || "";

  const published = getDisplayPublished(article);
  const articleUrl = article.url ? escapeAttribute(article.url) : "#";

  // ✅ Get sentiment badge if available
  const sentiment = article.sentiment || "";
  const sentimentBadge = sentiment
    ? `<span class="sentiment-badge ${getSentimentClass(sentiment)} ms-2" aria-label="Article sentiment">${sentiment}</span>`
    : "";

  // ✅ Get readability scores if available
  const readingEase = article.readingEase;
  const gradeLevel = article.gradeLevel;
  const hasReadability = readingEase !== undefined && gradeLevel !== undefined;

  const readabilityBadge = hasReadability
    ? `<span class="badge bg-info text-dark ms-2" title="Readability Metrics">📖 Flesch Reading Ease: ${readingEase} | Flesch-Kincaid Difficulty Level: ${gradeLevel}</span>`
    : "";

  // Show source button if we have a name (ID is optional since News API often returns null for id)
  const actionItems = [];
  const hasSource = sourceName && sourceName !== "Unknown source";

  if (hasSource) {
    actionItems.push(
      `<a class="btn btn-sm btn-outline-primary w-100" href="/source?sourceId=${encodeURIComponent(sourceId || sourceName)}&sourceName=${encodeURIComponent(sourceName)}">Source info</a>`
    );
  } else {
    actionItems.push(`<span class="badge bg-secondary text-center">Source unavailable</span>`);
  }

  const wordStatsQuery = [article.title, article.description, query]
    .map((value) => (value || "").trim())
    .find((value) => value.length > 0) || "";

  if (wordStatsQuery) {
    const langQuery = language && language.trim() ? `&lang=${encodeURIComponent(language.trim())}` : "";
    actionItems.push(
      `<a class="btn btn-sm btn-outline-secondary w-100" href="/wordstats?q=${encodeURIComponent(wordStatsQuery)}${langQuery}">Word Stats</a>`
    );
  }

  const actionsColumn = actionItems.length
    ? `<div class="ms-auto flex-shrink-0 d-flex flex-column align-items-stretch gap-2">${actionItems.join("")}</div>`
    : "";

  const footer = [];
  if (sourceName) footer.push(`From ${sourceName}`);
  if (published) footer.push(published);

  return `
    <article class="card shadow-sm border-0 mb-3">
      <div class="card-body">
        <div class="d-flex align-items-start gap-3 article-card-header">
          <div class="flex-grow-1">
            <h3 class="h5 card-title mb-2">
              <a href="${articleUrl}" target="_blank" rel="noopener">${title}</a>${sentimentBadge}${readabilityBadge}
            </h3>
            ${description ? `<p class="card-text small mb-2">${description}</p>` : ""}
          </div>
          ${actionsColumn}
        </div>
        ${footer.length ? `<div class="text-muted small">${footer.join(" · ")}</div>` : ""}
      </div>
    </article>`;
}

function initSourceDirectory() {
  const root = document.getElementById("source-page");
  if (!root) return;

  const searchInput = document.getElementById("source-search-input");
  const searchResults = document.getElementById("source-search-results");
  const searchSummary = document.getElementById("source-search-summary");
  const profileName = document.getElementById("source-profile-name");
  const profileDescription = document.getElementById("source-profile-description");
  const profileLink = document.getElementById("source-profile-link");
  const profileMeta = document.querySelectorAll("#source-profile-meta [data-field]");
  const articlesContainer = document.getElementById("source-articles");
  const articlesSummary = document.getElementById("source-articles-summary");

  const preselectedId = root.dataset.selectedId || "";
  const preselectedName = root.dataset.selectedName || "";
  if (preselectedName) searchInput.value = preselectedName;

  let sources = [];
  const sourcesById = new Map();
  let selectedSourceId = "";

  loadSourcesList().then(() => {
    if (preselectedId) {
      const existing = sourcesById.get(preselectedId);
      if (existing) {
        selectSource(existing);
      } else {
        selectSource({ id: preselectedId, name: preselectedName, language: "all" });
      }
    }
  });

  searchInput.addEventListener("input", () => {
    const rawTerm = searchInput.value.trim();
    const term = rawTerm.toLowerCase();
    const matches = term
      ? sources.filter((source) => source.name.toLowerCase().includes(term))
      : sources.slice(0, 15);

    toggleSearchResultsVisibility(rawTerm);
    renderSearchMatches(matches, rawTerm);
  });

  searchResults.addEventListener("click", (event) => {
    const item = event.target.closest("button[data-source-id]");
    if (!item) return;
    const id = item.dataset.sourceId;
    const source = sourcesById.get(id) || { id, name: item.dataset.sourceName || item.textContent, language: "all" };
    selectSource(source);
  });

  async function loadSourcesList() {
    try {
      const response = await fetch(`/sources?language=all`);
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const payload = await response.json();
      if (payload.status === "error") {
        throw new Error(payload.message || "Failed to load sources");
      }
      sources = Array.isArray(payload.sources)
        ? payload.sources
            .filter((src) => src && src.id)
            .map((src) => ({ ...src, name: src.name || src.id }))
        : [];
      sources.sort((a, b) => (a.name || a.id).localeCompare(b.name || b.id));
      sources.forEach((source) => sourcesById.set(source.id, source));
      searchSummary.textContent = `Loaded ${sources.length} sources`;
      toggleSearchResultsVisibility("");
      renderSearchMatches(sources.slice(0, 15), "");
    } catch (error) {
      console.warn("Failed to load sources list:", error);
      searchResults.innerHTML = `<div class="list-group-item text-muted">Unable to load sources.</div>`;
      searchSummary.textContent = "";
      toggleSearchResultsVisibility("");
    }
  }

  function renderSearchMatches(matches, term) {
    if (!matches.length) {
      searchResults.innerHTML = `<div class="list-group-item text-muted">No sources match "${escapeHtml(term)}".</div>`;
      return;
    }

    const html = matches.slice(0, 15).map((source) => {
      const name = highlightText(source.name, term);
      const description = source.description ? escapeHtml(source.description) : "";
      return `
        <button type="button" class="list-group-item list-group-item-action" data-source-id="${escapeAttribute(source.id)}" data-source-name="${escapeAttribute(source.name)}">
          <div class="fw-semibold">${name}</div>
          ${description ? `<div class="small text-muted">${description}</div>` : ""}
        </button>`;
    }).join("");

    searchResults.innerHTML = html;

    if (selectedSourceId) {
      const selector = `[data-source-id="${escapeCssIdentifier(selectedSourceId)}"]`;
      const activeItem = searchResults.querySelector(selector);
      if (activeItem) activeItem.classList.add("active");
    }
  }

  function selectSource(source) {
    if (!source || !source.id) return;
    selectedSourceId = source.id;
    if (source.name) searchInput.value = source.name;

    toggleSearchResultsVisibility(searchInput.value);
    highlightActiveSelection();
    updateProfileDetails(source);
    fetchSourceProfile(source.id);
  }

  function highlightActiveSelection() {
    searchResults.querySelectorAll(".list-group-item").forEach((item) => {
      item.classList.toggle("active", item.dataset.sourceId === selectedSourceId);
    });
  }

  function updateProfileDetails(source) {
    profileName.textContent = source.name || "Source details";
    profileDescription.textContent = source.description || "Latest information pulled from News API.";

    const url = source.url;
    if (url) {
      profileLink.href = url;
      profileLink.classList.remove("disabled");
    } else {
      profileLink.href = "#";
      profileLink.classList.add("disabled");
    }

    profileMeta.forEach((node) => {
      const key = node.dataset.field;
      const value = source[key] || "—";
      node.textContent = value ? value.toString() : "—";
    });
  }

  async function fetchSourceProfile(sourceId) {
    articlesSummary.textContent = "Loading…";
    articlesContainer.innerHTML = `<div class="text-muted">Loading articles…</div>`;

    const params = new URLSearchParams({
      sourceId,
      language: "all",
      pageSize: "10",
      sortBy: "publishedAt",
    });

    try {
      const response = await fetch(`/sources?${params.toString()}`);
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const payload = await response.json();

      if (payload.status === "error") {
        throw new Error(payload.message || "Failed to load source profile");
      }

      if (payload.source) {
        const merged = { ...sourcesById.get(sourceId), ...payload.source };
        sourcesById.set(sourceId, merged);
        updateProfileDetails(merged);
      }

      const articles = Array.isArray(payload.articles) ? payload.articles : [];
      renderSourceArticles(articles, payload.totalResults || articles.length);
    } catch (error) {
      articlesSummary.textContent = "";
      articlesContainer.innerHTML = `<div class="text-danger">${escapeHtml(error.message)}</div>`;
    }
  }

  function renderSourceArticles(articles, total) {
    if (!articles.length) {
      articlesSummary.textContent = "Showing 0 of 0";
      articlesContainer.innerHTML = `<p class="text-muted mb-0">No recent articles were returned for this source.</p>`;
      return;
    }

    articlesSummary.textContent = `Showing ${articles.length} of ${total}`;

    const list = articles.map((article) => {
      const title = highlightText(article.title || "Untitled", searchInput.value.trim());
      const desc = article.description ? escapeHtml(article.description) : "";
      const published = getDisplayPublished(article);
      const url = article.url ? escapeAttribute(article.url) : "#";
      return `
        <article class="border-bottom py-2">
          <a class="fw-semibold" href="${url}" target="_blank" rel="noopener">${title}</a>
          ${published ? `<div class="small text-muted">${published}</div>` : ""}
          ${desc ? `<div class="small">${desc}</div>` : ""}
        </article>`;
    }).join("");

    articlesContainer.innerHTML = list;
  }

  function toggleSearchResultsVisibility(term) {
    const hasTerm = term && term.trim().length > 0;
    // Always show the results list so users can see matches while typing.
    searchResults.classList.remove("d-none");
    // Hide the "Loaded X sources" summary only when actively filtering.
    searchSummary.classList.toggle("d-none", hasTerm);
  }
}

function escapeHtml(str) {
  return String(str)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

function escapeAttribute(str) {
  return escapeHtml(str).replace(/`/g, "&#96;");
}

function escapeCssIdentifier(str) {
  if (typeof CSS !== "undefined" && typeof CSS.escape === "function") {
    return CSS.escape(str);
  }
  return String(str).replace(/[^a-zA-Z0-9_-]/g, (ch) => `\\${ch}`);
}

function highlightText(text, query) {
  const safe = escapeHtml(text || "");
  if (!query) return safe;
  // Escape regex metacharacters in the query
  const escaped = query.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  // Match the query only when it’s not attached to letters/numbers on either side.
  // Works for single words ("AI") and phrases ("climate change").
  const re = new RegExp(`(?<![\\p{L}\\p{N}])${escaped}(?![\\p{L}\\p{N}])`, "giu");
  return safe.replace(re, m => `<mark>${m}</mark>`);
}

function formatRelativeDate(isoString) {
  const parsed = new Date(isoString);
  if (Number.isNaN(parsed.getTime())) return "";

  const now = new Date();
  const diffMs = now - parsed;
  const diffMinutes = Math.floor(diffMs / 60000);
  if (diffMinutes < 1) return "Just now";
  if (diffMinutes < 60) return `${diffMinutes} minute${diffMinutes === 1 ? "" : "s"} ago`;
  const diffHours = Math.floor(diffMinutes / 60);
  if (diffHours < 24) return `${diffHours} hour${diffHours === 1 ? "" : "s"} ago`;
  const diffDays = Math.floor(diffHours / 24);
  if (diffDays < 7) return `${diffDays} day${diffDays === 1 ? "" : "s"} ago`;
  return parsed.toLocaleDateString(undefined, {
    year: "numeric",
    month: "short",
    day: "numeric",
  });
}
