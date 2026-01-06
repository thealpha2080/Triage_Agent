// ---------- DOM ----------
const chat = document.getElementById("chat");
const msgInput = document.getElementById("msgInput");
const sendBtn = document.getElementById("sendBtn");

// Tabs / views
const tabButtons = document.querySelectorAll(".tab");
const views = document.querySelectorAll(".view");

// ---------- Session ----------
const sessionId = localStorage.getItem("sessionId") || crypto.randomUUID();
localStorage.setItem("sessionId", sessionId);

// ---------- View switching ----------
function setActiveView(viewId) {
  views.forEach(v => v.classList.toggle("active", v.id === viewId));
  tabButtons.forEach(b => b.classList.toggle("active", b.dataset.view === viewId));
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
