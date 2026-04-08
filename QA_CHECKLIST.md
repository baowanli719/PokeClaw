# PokeClaw E2E QA Checklist

Every build must pass ALL checks before shipping. Run on Pixel 8 Pro (or equivalent).

## Prerequisites
- [ ] Accessibility service enabled
- [ ] Cloud LLM configured (API key set)
- [ ] Local LLM downloaded (Gemma 4)
- [ ] WhatsApp installed with at least 1 contact ("Girlfriend")

---

## A. Cloud LLM — Chat

- [ ] **A1. Pure chat question**: "what is 2+2" → answer in bot bubble, 1 round, no tools, no rocket, no "Starting task...", no "Reading screen..."
- [ ] **A2. Follow-up chat**: after A1, ask "what about 3+3" → answer in bot bubble, context preserved
- [ ] **A3. Chat then task**: chat "hello" → get reply → then "send hi to Girlfriend on WhatsApp" → task executes correctly
- [ ] **A4. Task then chat**: "send hi to Girlfriend on WhatsApp" → completes → then "how are you" → chat reply (not task)
- [ ] **A5. Multiple chat messages**: send 3 chat messages in a row → all get bot bubble replies

## B. Cloud LLM — Tasks

- [ ] **B1. Send message**: "send hi to Girlfriend on WhatsApp" → send_message tool called → message sent → answer in bot bubble
- [ ] **B2. Complex task**: "open YouTube and search for funny cat videos" → opens YouTube → searches → multiple steps shown
- [ ] **B3. Task with context**: "I'm arguing with my girlfriend" → then "send sorry to Girlfriend on WhatsApp" → message content should reflect context
- [ ] **B4. Failed task**: "send hi to NonExistentPerson on WhatsApp" → error message in bot bubble (not stuck)

## C. Cloud LLM — Monitor Workflow

- [ ] **C1. Start monitor**: "monitor Girlfriend on WhatsApp" → top bar shows "Monitoring: Girlfriend" → presses Home
- [ ] **C2. Auto-reply triggers**: Girlfriend sends message → notification caught → WhatsApp opens → reads context → Cloud LLM generates reply → reply sent
- [ ] **C3. Stop monitor**: tap top bar → expand → Stop → monitoring stops

## D. Local LLM — Chat

- [ ] **D1. Pure chat**: switch to Local LLM → "hello" → on-device reply in bot bubble
- [ ] **D2. Chat tab has no task ability**: type "open YouTube" in Chat tab → LLM responds conversationally (doesn't try to control phone)

## E. Local LLM — Task Tab

- [ ] **E1. No input bar**: Task tab has workflow cards only, no text input
- [ ] **E2. Monitor workflow**: tap Monitor card → dialog → enter "Girlfriend" → Start → monitoring activates
- [ ] **E3. Send message card**: tap Send Message card → dialog → fills contact/message → sends

## F. Task Lifecycle UI

- [ ] **F1. Top bar during task**: while task runs → orange "Task running..." + red "Stop" button visible
- [ ] **F2. Send button becomes stop**: while task runs → send button turns red X → tapping it cancels task
- [ ] **F3. Floating button during task**: while task runs in another app → floating circle shows pill with step/tokens + "Tap to stop"
- [ ] **F4. Floating button stop**: tap floating button during task → task cancels
- [ ] **F5. Second task works**: complete task 1 → start task 2 → floating button, top bar, stop button all work
- [ ] **F6. No stuck typing indicator**: after task completes → "..." is replaced by answer or removed

## G. Welcome Screen

- [ ] **G1. Cloud welcome**: new chat → "Cloud LLM enabled" + task examples + subtitle about instructions
- [ ] **G2. Local welcome**: switch to Local → new chat → "Local LLM enabled" + chat examples + subtitle about Task tab
- [ ] **G3. Cloud prompt tap**: tap suggested prompt → stays in Chat tab (not jump to Task)

## H. General UI

- [ ] **H1. Floating button size**: small circle on home screen (not giant)
- [ ] **H2. Keyboard in LLM Config**: Settings → LLM Config → tap API key → keyboard doesn't block field
- [ ] **H3. Layout sizes**: all text/buttons normal size (dp not pt)
- [ ] **H4. Model switcher**: tap model bar → dropdown → switch model → status updates
- [ ] **H5. New chat**: tap pencil icon → clears messages → shows welcome screen

---

## Known Issues (to fix)
- Floating button flashes briefly on chat questions (TASK_NOTIFY → SUCCESS)
- "Accessibility service starting..." appears on every new chat
- Floating button may disappear when navigating to other apps during long tasks
- Top bar "Task running..." not reliably showing (isProcessing state issue)
- Send button not turning red during task execution
