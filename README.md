“Tirage Agent” - A Bot that responds to users' input self-determined symptoms and then outputs a generalized response that clarifies if the user needs over-the-counter medicine, a doctor to see them, or if they should go straight to the emergency room. The bot will take input and tokenize it, scanning for flag words like ‘shortness of breath,’ even if misspelled, as an external database/library will assist in the scanning of the responses. The experience will feel almost like an LLM, but there will be no keys, verification or anything like that required for this program.

NOTE: NO INFO FROM THE TRIAGE BUDDY IS TO BE CONSIDERED AS MEDICAL ADVICE. 
This bot is purely demonstrational, and its main purpose is to demonstrate the idea of a chatbot being used as a tool to help someone figure out how severe their condition is. No actual diagnosis is being made.
The bot can also be considered as a health tracker to provide a summary of the user’s current condition for trained medical personnel to see. 



TECHNICAL SETUP:
Architecture
Frontend: HTML/CSS/JS chat UI running in a browser
Backend: Java server running on the client side
Data: local JSON/CSV saves, will include symptoms, rules, logins, and other info.

CHAT UI:
Chat bubbles
Quick-reply buttons for clarification
‘Detected Symptoms’ chips or dropdown, which the user can remove/add
Result card (triage and reasons along with next steps)

CLARIFYING QUESTIONS ENGINE:
Triggered when a message/prompt is too vague, or the list of possibilities isn't narrow enough to generate a plausible outcome.
The bot should ask for more intel on severity, duration, etc

RULE ENGINE/DECISION LOGIC
Create flags for keywords that have to do with health or medicine, like ‘fever’, ‘cough’, and ‘light-headed.’
If a keyword triggers a set of potentially dangerous signs or symptoms, mark this token as a ‘red flag.’ 
If any red flags are found, automatically triage to the ER for immediate attention.
If none of the flags are red, then determine whether the situation can be dealt with without or with a doctor's intervention or prescription. 

PERSISTENCE/HISTORY:
Save every past chat + extracted symptoms + final triage as JSON (individual cases)
Optional feature: 
Have a history tab/page that includes all past cases
Can search by keyword/symptom
Sort by date/urgency/confidence
