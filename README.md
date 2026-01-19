# Triage Agent

Triage Agent is a bot that responds to users' self-described symptoms and then outputs a general response that clarifies if the user needs over-the-counter medicine, a doctor, or an emergency room. The bot takes input, tokenizes it, and scans for flag words like "shortness of breath," even if misspelled. The idea is to feel like an LLM (i.e. ChatGPT, Gemini, Copilot, etc), but it runs locally with no keys or verification.

## Safety note

NOTE: NO INFO FROM THE TRIAGE BUDDY IS TO BE CONSIDERED AS MEDICAL ADVICE.
This bot is purely demonstrational, and its main purpose is to demonstrate the idea of a chatbot being used as a tool to help someone figure out how severe their condition is. No actual diagnosis is being made.
The bot can also be considered as a health tracker to provide a summary of the user’s current condition for trained medical personnel to see. 



## Technical setup

### Architecture
- Frontend: HTML/CSS/JS chat UI running in a browser.
- Backend: Java server running on the client side.
- Data: local JSON/CSV saves, which include symptoms, rules, logins, and other info.
        - Implemented SQLite for a true database due to time constraints.

### Chat UI
- Chat bubbles.
- Result card (triage and reasons along with next steps).

### Clarifying questions engine
- Triggered when a message/prompt is too vague, or the list of possibilities isn't narrow enough.
- The bot should ask for more details on severity, duration, etc.

### Rule engine / decision logic
- Create flags for keywords that have to do with health or medicine, like "fever," "cough," and "light-headed."
- If a keyword triggers potentially dangerous signs or symptoms, mark that token as a "red flag."
- If any red flags are found, automatically triage to the ER for immediate attention.
- If none of the flags are red, then determine whether the situation can be dealt with without or with a doctor's intervention or prescription.

### Persistence/history
- Save every past chat + extracted symptoms + final triage as JSON (individual cases).
- Optional feature:
  - Have a history tab/page that includes all past cases.
  - Can search by keyword/symptom.
  - Sort by date/urgency/confidence.

## Project phases 

**Phase 1:**
- Collect basic info like duration and severity.
- Ask clarifying questions when the message is vague.
- Summarize the triage result at the end so the user can see the decision.

**Phase 2:**
- Show a clean list of detected symptoms inside the UI.
- Explain *why* the triage decision happened, using short reasons a user can read.

**Phase 3:**
- Add a history view so users can review past cases.
- Allow simple searching and filtering of those saved cases.

**Phase 4:**
- Add stronger storage options (like a database) so history is reliable and fast.
