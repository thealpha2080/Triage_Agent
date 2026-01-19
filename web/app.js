// Title: app.js
// Author: Ali Abbas
// Description: Client-side logic for chat and database tab rendering.
// Date: Sep 28, 2025
// Version: 1.1.0

const chat = document.getElementById("chat");
const msgInput = document.getElementById("msgInput");
const sendBtn = document.getElementById("sendBtn");
const caseRows = document.getElementById("caseRows");
const caseEmpty = document.getElementById("caseEmpty");
<script>
(function(){
  const root = document.documentElement; // <html>
  const btn = document.getElementById("themeToggle");
  const STORAGE_KEY = "theme"; // "light" | "dark"

  function applyTheme(theme){
    if(theme === "dark") root.setAttribute("data-theme", "dark");
    else root.removeAttribute("data-theme");

    // Button label
    if(btn){
      btn.textContent = (theme === "dark") ? "☀️ Light" : "🌙 Dark";
    }
  }

  // Load saved theme OR default to system preference
  const saved = localStorage.getItem(STORAGE_KEY);
  const systemPrefersDark = window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches;
  const initialTheme = saved || (systemPrefersDark ? "dark" : "light");
  applyTheme(initialTheme);

  // Toggle on click
  btn?.addEventListener("click", () => {
    const isDark = root.getAttribute("data-theme") === "dark";
    const next = isDark ? "light" : "dark";
    localStorage.setItem(STORAGE_KEY, next);
    applyTheme(next);
  });
})();
</script>


// Tabs / views
const tabButtons = document.querySelectorAll(".tab");
const views = document.querySelectorAll(".view");

// ---------- Session ----------
// Keep sessionId mutable so resetCase can start a brand-new session.
let sessionId = localStorage.getItem("sessionId") || crypto.randomUUID();
localStorage.setItem("sessionId", sessionId);

// ---------- View switching ----------
function setActiveView(viewId) {
  views.forEach(v => v.classList.toggle("active", v.id === viewId));
  tabButtons.forEach(b => b.classList.toggle("active", b.dataset.view === viewId));
  if (viewId === "dbView") {
    loadCases();
  }
}

tabButtons.forEach(btn => {
  btn.addEventListener("click", () => setActiveView(btn.dataset.view));
});

// ---------- Chat rendering ----------
function addBubble(text, who) {
  const div = document.createElement("div");
  div.className = `bubble ${who}`;
  div.textContent = text;
  chat.appendChild(div);
  chat.scrollTop = chat.scrollHeight;
}

// ---------- Send message ----------
async function sendMessage() {
  const text = msgInput.value.trim();
  if (!text) return;

  addBubble(text, "user");
  msgInput.value = "";
  msgInput.focus();

  const payload = { sessionId, text };

  try {
    const res = await fetch("/api/message", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload)
    });

    if (!res.ok) {
      addBubble(`Server error (${res.status}). Try again.`, "bot");
      return;
    }

    const data = await res.json();
    // If the case is locked, show the summary and a reset button
        if (data.locked) {
          addBubble(data.text || "No response text", "bot");
          showResetButton();
          return;
        }
    addBubble(data.text || "No response text", "bot");
  } catch (err) {
    addBubble("Network error. Is the Java server running?", "bot");
  }
}

// ---------- Events ----------
sendBtn.addEventListener("click", sendMessage);
msgInput.addEventListener("keydown", (e) => {
  if (e.key === "Enter") sendMessage();
});

// ---------- First message ----------
addBubble("Hi, I’m Triage Bot. I’m here to help you understand how serious things might be. Tell me what’s going on, and I’ll ask a few questions to guide you.", "bot");

// ---------- Database view ----------
async function loadCases() {
  if (!caseRows) return;
  caseRows.innerHTML = "";
  caseEmpty.style.display = "none";

  try {
    // Fetch the latest summaries for the Database tab.
    const res = await fetch("/api/cases");
    if (!res.ok) {
      caseEmpty.textContent = `Failed to load cases (${res.status}).`;
      caseEmpty.style.display = "block";
      return;
    }
    const data = await res.json();
    if (!Array.isArray(data) || data.length === 0) {
      caseEmpty.textContent = "No cases saved yet.";
      caseEmpty.style.display = "block";
      return;
    }
    data.forEach(caseItem => renderCaseRow(caseItem));
  } catch (err) {
    caseEmpty.textContent = "Failed to load cases. Is the Java server running?";
    caseEmpty.style.display = "block";
  }
}

function renderCaseRow(item) {
  const row = document.createElement("div");
  row.className = "case-row";

  const started = formatDate(item.startedEpochMs);
  row.appendChild(makeCell(started));
  row.appendChild(makeCell(item.triageLevel || "—"));
  row.appendChild(makeCell(item.severity || "—"));
  row.appendChild(makeCell(item.duration || "—"));
  row.appendChild(makeCell(String(item.notesCount ?? 0)));
  row.appendChild(makeCell(String(item.redFlagCount ?? 0)));

  caseRows.appendChild(row);
}

function makeCell(text) {
  const div = document.createElement("div");
  div.textContent = text;
  return div;
}

function formatDate(epochMs) {
  if (!epochMs) return "—";
  const date = new Date(Number(epochMs));
  if (Number.isNaN(date.getTime())) return "—";
  return date.toLocaleString();
}

// ---------- Reset handling ----------
function showResetButton() {
  const div = document.createElement("div");
  div.className = "bubble bot";
  const btn = document.createElement("button");
  btn.textContent = "Start a new case";
  btn.className = "reset-btn";
  btn.addEventListener("click", resetCase);
  div.appendChild(btn);
  chat.appendChild(div);
  chat.scrollTop = chat.scrollHeight;
}

function resetCase() {
  // Clear chat bubbles
  chat.innerHTML = "";
  // New session id
  const newId = crypto.randomUUID();
  sessionId = newId;
  localStorage.setItem("sessionId", newId);
  // Fresh intro
  addBubble("New case started. Tell me what’s going on (symptoms, duration, severity).", "bot");
}

// ---------- UI helpers ----------
function addOptions(options) {
  const container = document.createElement("div");
  container.className = "option-row";
  options.forEach(opt => {
    const btn = document.createElement("button");
    btn.textContent = opt;
    btn.className = "option-btn";
    btn.addEventListener("click", () => {
      msgInput.value = opt;
      sendMessage();
    });
    container.appendChild(btn);
  });
  chat.appendChild(container);
  chat.scrollTop = chat.scrollHeight;
}

function addBadgeRow(title, items) {
  const div = document.createElement("div");
  div.className = "badge-row";
  const label = document.createElement("div");
  label.className = "badge-title";
  label.textContent = title + ":";
  div.appendChild(label);
  items.forEach(txt => {
    const badge = document.createElement("span");
    badge.className = "badge";
    badge.textContent = txt;
    div.appendChild(badge);
  });
  chat.appendChild(div);
  chat.scrollTop = chat.scrollHeight;
}
