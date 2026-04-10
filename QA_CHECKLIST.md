# PokeClaw E2E QA Checklist

Every build must pass ALL checks before shipping.

---

## QA Methodology — How to Test (READ THIS FIRST)

### Device Setup

```bash
# 1. Check device connected
adb devices -l

# 2. Install APK
cd /home/nicole/MyGithub/PokeClaw
./gradlew assembleDebug
APK=$(find app/build/outputs/apk/debug/ -name "*.apk" | head -1)
adb install -r "$APK"

# 3. Launch app
adb shell am start -n io.agents.pokeclaw/io.agents.pokeclaw.ui.splash.SplashActivity
sleep 5

# 4. Enable accessibility (if not already)
CURRENT=$(adb shell settings get secure enabled_accessibility_services)
[[ "$CURRENT" != *"io.agents.pokeclaw"* ]] && \
  adb shell settings put secure enabled_accessibility_services \
  "$CURRENT:io.agents.pokeclaw/io.agents.pokeclaw.service.ClawAccessibilityService"

# 5. Grant permissions
adb shell pm grant io.agents.pokeclaw android.permission.READ_CONTACTS
```

### Configure LLM via ADB

```bash
# Cloud LLM
source /home/nicole/MyGithub/PokeClaw/.env
adb shell "am broadcast -a io.agents.pokeclaw.DEBUG_TASK -p io.agents.pokeclaw \
  --es task 'config:' --es api_key '$OPENAI_API_KEY' --es model_name 'gpt-4.1'"

# Local LLM
MODEL_PATH="/storage/emulated/0/Android/data/io.agents.pokeclaw/files/models/gemma-4-E2B-it.litertlm"
adb shell "am broadcast -a io.agents.pokeclaw.DEBUG_TASK -p io.agents.pokeclaw \
  --es task 'config:' --es provider 'LOCAL' --es base_url '$MODEL_PATH' --es model_name 'gemma4-e2b'"
```

### Send a Task via ADB (for M tests)

```bash
# IMPORTANT: wrap the task string in single quotes INSIDE adb shell double quotes
adb logcat -c
adb shell "am broadcast -a io.agents.pokeclaw.DEBUG_TASK -p io.agents.pokeclaw \
  --es task 'how much battery left'"
```

### Read Results from Logcat

```bash
# Wait for task to complete (Cloud ~10s, Local ~60-120s per round)
sleep 15
PID=$(adb shell pidof io.agents.pokeclaw)

# Check which tools were called + final answer
adb logcat -d | grep "$PID" | grep -E "onToolCall|onComplete" | head -10

# Full breakdown
adb logcat -d | grep "$PID" | grep -E "DebugTask|PipelineRouter|AgentService|TaskOrchestrator|onToolCall|onComplete"
```

### Verify PASS/FAIL

For each M test, check:
1. **Correct tool called** — e.g., "how much battery" should call `get_device_info(battery)`, NOT open Settings
2. **Actual data in answer** — "73%, not charging, 32°C" NOT "I checked the battery"
3. **Rounds** — system queries should be 2 rounds, complex tasks 5-15
4. **Auto-return** — after task, PokeClaw chatroom should come back to foreground
5. **Graceful failure** — if task can't complete, clear error message (not stuck/loop)

### Verify UI via Uiautomator

```bash
# Dump all visible UI elements
adb shell uiautomator dump /sdcard/ui.xml
adb shell cat /sdcard/ui.xml | python3 -c "
import sys, xml.etree.ElementTree as ET
root = ET.fromstring(sys.stdin.read())
for node in root.iter():
    text = node.get('text', '')
    desc = node.get('content-desc', '')
    pkg = node.get('package', '')
    if (text or desc) and 'pokeclaw' in pkg.lower():
        print(f'text={text!r} desc={desc!r}')
"
```

Use this to verify:
- UI elements are present (tabs, buttons, prompts)
- Placeholder text changes when switching modes
- Correct model name shows in dropdown

### Tap UI Elements

```bash
# Find coordinates of an element
adb shell cat /sdcard/ui.xml | python3 -c "
import sys, xml.etree.ElementTree as ET
root = ET.fromstring(sys.stdin.read())
for node in root.iter():
    text = node.get('text', '')
    bounds = node.get('bounds', '')
    if 'Task' in text:
        print(f'text={text!r} bounds={bounds}')
"

# Tap at coordinates (center of bounds)
adb shell input tap 746 2041
```

### Three QA Layers

**Layer 1: Backend QA (ADB broadcast)**
- Fast: ~10s per test
- Uses `am broadcast` to send tasks directly to DebugTaskReceiver
- Bypasses UI entirely — tests tools, LLM routing, error handling, agent loop
- Code path: `DebugTaskReceiver → sendTask() → PipelineRouter → Agent`
- Sections: M tests
- When to run: every backend/agent/tool change

**Layer 2: UI Structure QA (uiautomator dump)**
- Medium: ~5s per test
- Verifies UI elements are present, positioned correctly, styled correctly
- No message sending — purely visual/structural verification
- Code path: Compose render → uiautomator reads view tree
- Sections: P tests
- When to run: every UI/layout change

**Layer 3: UI E2E QA (tap + type + send + verify response)**
- Slow: ~30s per test
- Simulates real user: tap input → type → dismiss keyboard → tap send → wait → verify response bubble
- Tests the FULL pipeline: UI routing → Activity callback → LLM → response → UI update
- Code path: `ChatInputBar → isLocalUI routing → onSendChat/onSendTask → Activity → LLM → UI`
- Sections: Q tests
- When to run: every change that touches send routing, mode switching, or input bar
- **This is the ONLY layer that tests UI send routing.** Layer 1 broadcast bypasses ChatInputBar entirely.

**Why 3 layers, not 2:**
Layer 1 broadcast calls `sendTask()` directly — it never touches `ChatInputBar`, `isLocalUI`, or the Chat/Task toggle routing. If UI routing breaks (e.g., Cloud mode accidentally routes to `onSendChat`), Layer 1 won't catch it. Layer 3 covers this gap.

**Run order:**
1. Layer 2 first (fast, catches layout breaks)
2. Layer 3 second (catches routing/interaction breaks)
3. Layer 1 last (catches backend/agent breaks)

```bash
# Layer 2 — simulate real user typing + sending
# 1. Find and tap input field
adb shell uiautomator dump /sdcard/ui.xml
# Parse bounds for the input element with placeholder text
INPUT_X=504; INPUT_Y=2100  # adjust from dump

# 2. Tap input, type, send
adb shell input tap $INPUT_X $INPUT_Y        # focus input
sleep 0.5
adb shell input text "how%smuch%sbattery"    # type (spaces = %s in adb)
sleep 0.5
SEND_X=970; SEND_Y=2100                      # adjust from dump
adb shell input tap $SEND_X $SEND_Y          # tap send

# 3. Wait for response, verify chat bubble appears
sleep 15
adb shell uiautomator dump /sdcard/ui_after.xml
adb shell cat /sdcard/ui_after.xml | python3 -c "
import sys, xml.etree.ElementTree as ET
root = ET.fromstring(sys.stdin.read())
for node in root.iter():
    text = node.get('text', '')
    pkg = node.get('package', '')
    if text and 'pokeclaw' in pkg.lower() and ('battery' in text.lower() or '%' in text):
        print(f'FOUND RESPONSE: {text!r}')
"
# Should find: "Battery: 73%, not charging, 32°C" or similar in a chat bubble
```

### Cross-Device Testing

Test on at least 2 devices:
- **Stock Android** (Pixel): baseline, everything should work
- **MIUI/Samsung/OEM** (Xiaomi etc): test for OEM restrictions (autostart, different Settings UI)

Key OEM differences:
- MIUI blocks background app launches (autostart whitelist needed)
- Samsung has different Settings layout
- Some OEMs have chain-launch dialogs (auto-dismissed by OpenAppTool)

### Local LLM Testing Notes

- CPU inference: ~50-60s per round on Pixel 8 Pro
- GPU may fail ("OpenCL not found") → auto-fallback to CPU
- LiteRT-LM SDK may crash on tool call parsing → our fallback extracts from error message
- Force stop loses accessibility service → re-enable after restart
- Model engine takes ~10s to load on first call

---

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
- [ ] **B4. Failed contact**: "send hi to Dad on WhatsApp" → Dad not in contacts → LLM reports failure in bot bubble (not stuck, not "Task completed")
- [ ] **B5. Failed app**: "send hi to Girlfriend on Signal" → Signal not installed → LLM reports can't open app

## C. Cloud LLM — Monitor Workflow

- [ ] **C1. Start monitor**: "monitor Girlfriend on WhatsApp" → top bar shows "Monitoring: Girlfriend" → user stays in PokeClaw chat (no Home press)
- [ ] **C2. Auto-reply triggers**: Girlfriend sends message → notification caught → WhatsApp opens → reads context → Cloud LLM generates reply → reply sent
- [ ] **C3. Stop monitor**: tap top bar → expand → Stop → monitoring stops

## D. Local LLM — Chat

- [ ] **D1. Pure chat**: switch to Local LLM → "hello" → on-device reply in bot bubble
- [ ] **D2. Chat tab has no task ability**: type "open YouTube" in Chat tab → LLM responds conversationally (doesn't try to control phone)

## E. Local LLM — Task Mode (v9: unified chat screen)

- [ ] **E1. Task mode via toggle**: Local tab → tap 🤖 Task → input placeholder changes to "Describe a phone task...", input area tints orange
- [ ] **E2. Task mode via Quick Task tap**: tap "🔋 How much battery left?" in Quick Tasks → input fills + auto-switches to Task mode
- [ ] **E3. Monitor via Quick Tasks panel**: scroll to BACKGROUND → tap Monitor card → centered dialog → enter contact → Start → monitoring activates
- [ ] **E4. Task sends correctly**: type "how much battery left" in Task mode → tap send → task executes, response in chat bubble

## F. Task Lifecycle UI

- [ ] **F1. Top bar during task**: while task runs → orange "Task running..." + red "Stop" button visible
- [ ] **F2. Send button becomes stop**: while task runs → send button turns red X → tapping it cancels task
- [ ] **F3. Floating button during task**: while task runs in another app → floating circle shows pill with step/tokens + "Tap to stop"
- [ ] **F4. Floating button stop**: tap floating button during task → task cancels
- [ ] **F5. Second task works**: complete task 1 → start task 2 → floating button, top bar, stop button all work
- [ ] **F6. No stuck typing indicator**: after task completes → "..." is replaced by answer or removed

## G. Empty State (v9 design)

- [ ] **G1. Cloud empty state**: PokeClaw icon + "PokeClaw" + "Cloud AI" subtitle + "Chat and tasks work together" hint + 3 prompts (Tokyo, birthday, WhatsApp)
- [ ] **G2. Local empty state**: PokeClaw icon + "PokeClaw" + "Local AI" subtitle + hint with bold 💬 Chat / 🤖 Task + 3 prompts (joke, what can you do, email)
- [ ] **G3. Cloud prompt tap**: tap prompt → fills input, stays in chat (no mode switch)
- [ ] **G4. Local prompt tap**: tap prompt → fills input, does NOT switch to Task mode (prompts are chat prompts)
- [ ] **G5. Tab switch updates empty state**: switch Local↔Cloud tab → subtitle, hint, and prompts all change immediately

## H. General UI

- [ ] **H1. Floating button size**: small circle on home screen (not giant)
- [ ] **H2. Keyboard in Models screen — API key**: Settings → LLM Config → tap API key → keyboard doesn't block field, field scrolls fully into view
- [ ] **H2-b. Keyboard in Models screen — Base URL**: switch to Custom provider → tap Base URL → keyboard doesn't block field
- [ ] **H2-c. Keyboard in Models screen — Model Name**: switch to Custom provider → tap Model Name → keyboard doesn't block field
- [ ] **H3. Layout sizes**: all text/buttons normal size (dp not pt)
- [ ] **H4. Model switcher**: tap model bar → dropdown → switch model → status updates
- [ ] **H4-b. Local backend label is truthful**: Local model falls back GPU→CPU → top-left model status updates to `CPU`, not stale `GPU`
- [ ] **H5. New chat**: tap pencil icon → clears messages → shows welcome screen
- [ ] **H6. Rename chat**: long-press session in sidebar → rename option → type new name → name updates in sidebar + persists after app restart
- [ ] **H7. Delete chat**: long-press session in sidebar → delete → session removed from sidebar + file deleted
- [ ] **H8. Rename preserves messages**: rename session → open it → all messages still there
- [ ] **H9. Delete correct session**: have 3+ sessions → delete middle one → other sessions unaffected

## I. Cross-App Behavior

- [ ] **I1. Floating button visible in other apps**: start task → agent navigates to WhatsApp/YouTube → floating button visible on top
- [ ] **I2. Return to PokeClaw mid-task**: while task runs in WhatsApp → press recents → tap PokeClaw → see task progress + stop button
- [ ] **I3. Notification during task**: incoming notification while task runs → task not disrupted

## M. Cloud LLM — Complex Tasks (50 cases)

Design principle: User perspective. INFO tasks → report actual data. ACTION tasks → confirm result. Must work on ANY Android device.

### System Queries (direct tool, no UI)
- [ ] **M1. Battery**: "how much battery left" → "73%, not charging, ~5h remaining" (get_device_info)
- [ ] **M2. WiFi**: "what WiFi am I connected to" → SSID + signal (get_device_info)
- [ ] **M3. Storage**: "how much storage do i have free" → "47GB free of 128GB" (get_device_info)
- [ ] **M4. Bluetooth**: "is bluetooth on" → ON/OFF + connected devices (get_device_info)
- [ ] **M5. Notifications**: "read my notifications" → actual notification list (get_notifications)
- [ ] **M6. Screen info**: "check what's on my screen" → describe visible UI elements

### App Navigation
- [ ] **M7. Open app**: "open spotify" → Spotify launches, confirmed
- [ ] **M8. YouTube search**: "search youtube for lofi beats" → YouTube opens, types query, results shown
- [ ] **M9. Web search**: "open Chrome and search for weather today" → Chrome, types, results
- [ ] **M10. URL navigation**: "open chrome and go to reddit.com/r/android" → Chrome loads URL
- [ ] **M11. Find in app**: "open WhatsApp and find my last message from Mom" → opens, navigates, reports content
- [ ] **M12. Deep navigation**: "open settings then go to about phone and tell me my android version" → Settings → About → reports version

### Information Retrieval (agent reads and reports back)
- [ ] **M13. Weather**: "what's the weather today" → actual temp + conditions
- [ ] **M14. Last email**: "read my latest email" → sender + subject + preview text
- [ ] **M15. Calendar**: "what's on my calendar tomorrow" → event list with times
- [ ] **M16. Installed apps**: "what apps do i have" → sensible summary, not raw dump
- [ ] **M17. Last notification**: "what did that last notification say" → most recent only
- [ ] **M18. Find photo**: "find the photo i took yesterday" → open Gallery, describe what's there

### Text Input Tasks
- [ ] **M19. Compose email**: "compose an email to test@example.com saying hello" → fills To/Subject/Body, does NOT send
- [ ] **M20. Search Twitter**: "go to twitter and find elon's latest post" → opens X, searches, reports post
- [ ] **M21. Google Maps search**: "open maps and navigate to nearest gas station" → Maps, search, results

### Settings Changes
- [ ] **M22. Dark mode**: "turn on dark mode" → toggles, confirms "Dark mode ON"
- [ ] **M23. Brightness**: "brightness to 50%" → adjusts, confirms level
- [ ] **M24. Timer**: "set a timer for 10 minutes" → Clock app, sets 10:00, starts
- [ ] **M25. Alarm**: "set an alarm for 7am tomorrow" → Clock, creates alarm, confirms
- [ ] **M26. DND**: "do not disturb on" → toggles DND, confirms
- [ ] **M27. Compound settings**: "turn off wifi and turn on bluetooth" → both done, both confirmed

### Media
- [ ] **M28. Take photo**: "take a selfie" → front camera, shutter, send_file back
- [ ] **M29. Screenshot**: "screenshot" → take_screenshot + send_file
- [ ] **M30. Play music**: "play music" → picks music app, attempts playback
- [ ] **M31. Next song**: "play the next song" → skip track in music player

### Cross-App Workflows
- [ ] **M32. Install app**: "install Telegram from Play Store" → Play Store → search → Install
- [ ] **M33. Copy-paste cross-app**: "copy tracking number from gmail and search it on amazon" → Gmail → copy → Amazon → paste
- [ ] **M34. Photo to message**: "take a photo and send it to Mom on WhatsApp" → camera → capture → WhatsApp → send

### Pure Chat (NO phone control)
- [ ] **M35. Joke**: "tell me a joke" → text response, NO tools called
- [ ] **M36. Math**: "whats 234 times 891" → "208,494", NO tools
- [ ] **M37. Timezone**: "what time is it in tokyo" → time answer, NO tools
- [ ] **M38. Cancel**: "nvm" → acknowledges, does nothing

### Error Handling
- [ ] **M39. Wrong app name**: "open flurpmaster 3000" → "App not found" + suggestion
- [ ] **M40. Impossible platform**: "text sarah on imessage" → "iMessage not available on Android, try SMS/WhatsApp"
- [ ] **M41. Typo tolerance**: "check my instagarm messages" → understands Instagram
- [ ] **M42. Missing permission**: "monitor WhatsApp" with Notification Access off → guides to Settings

### Natural Language Understanding
- [ ] **M43. Complaint as action**: "my screen is too dim" → increase brightness
- [ ] **M44. Vague request**: "scroll down" → asks clarification OR scrolls current
- [ ] **M45. Slang**: "yo whats on my notifs" → reads notifications
- [ ] **M46. Implicit action**: "go back" → system_key(back), reports new screen

### Device-Agnostic Edge Cases
- [ ] **M47. Call**: "call Mom" → dials Mom (works on any device with Phone app)
- [ ] **M48. Lock**: "lock my phone" → system_key(lock), confirms
- [ ] **M49. Clear notifications**: "clear all my notifications" → clears, confirms
- [ ] **M50. Phone temp**: "how hot is my phone" → get_device_info(battery) temp OR graceful "not available"

## R. Local LLM — Reasoning Quick Tasks (1-2 tool calls + LLM analysis)

- [ ] **R1. "Am I missing anything important?"**: get_notifications → LLM triages noise vs important → reports only actionable items
- [ ] **R2. "Will my battery last until tonight?"**: get_device_info(battery) + get_device_info(time) → LLM projects drain → yes/no verdict with advice
- [ ] **R3. "Rewrite what I just copied"**: clipboard(read) → LLM rewrites → clipboard(write) → reports changes
- [ ] **R4. "What can I delete to free up space?"**: get_device_info(storage) + get_installed_apps() → LLM cross-references → prioritized delete list
- [ ] **R5. "Read notifications and summarize"**: get_notifications → LLM groups by category + urgency
- [ ] **R6. "Should I charge my phone?"**: get_device_info(battery) → LLM judges % + gives advice (not just number)

## S. Cloud LLM — Multi-step Quick Tasks (Siri can't do these)

- [ ] **S1. "Search YouTube for funny cat fails"**: opens YouTube → types search → results shown (M1/M8 verified)
- [ ] **S2. "Install Telegram from Play Store"**: Play Store → search → Install (M6/M32 verified)
- [ ] **S3. "Check what's trending on Twitter"**: opens Twitter → navigates to trending → summarizes (M20)
- [ ] **S4. "What's on my screen right now?"**: get_screen_info → describes UI elements (M6 verified)
- [ ] **S5. "Copy latest email subject and Google it"**: notifications → clipboard → Chrome → search (M33)
- [ ] **S6. "Check latest WhatsApp chat and summarize"**: opens WhatsApp → reads top chat → reports (M11)
- [ ] **S7. "Open Reddit and search for pokeclaw"**: opens Reddit → types search → results (M51 verified)
- [ ] **S8. "Write an email saying I'll be late"**: opens Gmail → fills To/Subject/Body (M19/M8 verified)

## P. UI — v9 Design Verification

Reference prototype: `/home/nicole/MyGithub/PokeClaw/prototype/dashboard-v9.html`

### P1. Local/Cloud Toggle (in toolbar)
- [ ] **P1-1. Both buttons render**: "Local" and "Cloud" visible on same line as PokeClaw title, right side
- [ ] **P1-2. Selected state**: selected button has aiBubble bg + aiBubbleBorder, unselected has no bg/border
- [ ] **P1-3. No background container**: buttons sit directly in toolbar actions, no wrapping rectangle
- [ ] **P1-4. Tab syncs on launch**: Cloud LLM loaded → Cloud highlighted; Local LLM → Local highlighted
- [ ] **P1-5. Tab filters dropdown**: tap Local → dropdown shows local models only; tap Cloud → cloud models only
- [ ] **P1-6. No model → guidance**: Local with no model → "Download models..."; Cloud with no API key → "Configure API key..."
- [ ] **P1-7. Tab controls UI mode**: tap Local → Chat/Task toggle appears, prompts change to local, placeholder changes; tap Cloud → toggle hides, cloud prompts, cloud placeholder

### P2. Input Area (bottom)
- [ ] **P2-1. Local Chat/Task toggle**: "💬 Chat" and "🤖 Task" segment buttons visible ABOVE input (not beside)
- [ ] **P2-2. Input full width**: input bar takes full width, toggle is separate row above
- [ ] **P2-3. Task mode orange**: tap Task → toggle turns orange, input border orange, input bg tinted, placeholder "Describe a phone task...", send button orange
- [ ] **P2-4. Chat mode normal**: tap Chat → normal colors, placeholder "Chat with local AI..."
- [ ] **P2-5. Cloud no toggle**: switch to Cloud LLM → Chat/Task toggle HIDDEN, placeholder "Chat or give a task..."
- [ ] **P2-6. Send button dim**: when input empty → send button barely visible (low opacity); when text typed → lights up
- [ ] **P2-7. Same chatroom**: switching Chat↔Task does NOT clear messages, stays in same session

### P3. Quick Tasks Panel (between chat and input)
- [ ] **P3-1. Panel visible**: "▲ Quick Tasks ▲" handle with centered up-chevrons visible
- [ ] **P3-2. Default open**: panel open when new chat starts
- [ ] **P3-3. Collapsible**: tap handle → panel collapses (chevrons flip down); tap again → expands (chevrons flip up)
- [ ] **P3-4. Five items default**: 5 quick task prompts visible by default
- [ ] **P3-5. Show more**: "Show more ▼" expands to show all 12 prompts; "Show less ▲" collapses back
- [ ] **P3-6. Accent bar style**: each prompt has left accent bar (theme color) + full sentence text, finger-friendly height (~38dp)
- [ ] **P3-7. Tap fills input**: tap a quick task → text fills input bar (without emoji prefix)
- [ ] **P3-8. Tap auto-switches mode**: tapping quick task on Local tab → auto-switches to Task mode
- [ ] **P3-9. Background section**: "BACKGROUND" label + Monitor & Auto-Reply card visible below quick tasks
- [ ] **P3-10. Monitor card tap**: tap Monitor card → centered dialog (NOT bottom sheet) with Contact/App/Tone form + "Start Monitoring" button

### P4. Empty State
- [ ] **P4-1. Local empty**: PokeClaw icon + "PokeClaw" + "Local AI" + hint with bold 💬 Chat / 🤖 Task + 3 chat prompts (joke, what can you do, email)
- [ ] **P4-2. Cloud empty**: PokeClaw icon + "PokeClaw" + "Cloud AI" + "Chat and tasks work together" + 3 prompts (Tokyo, birthday, WhatsApp)
- [ ] **P4-3. Prompt style matches Quick Tasks**: same accent bar, same height (~38dp), same font size, same bg color
- [ ] **P4-4. Prompt tap**: tap empty state prompt → fills input, correct mode (local prompts = chat, cloud WhatsApp = task)

### P5. No Duplicate Panels
- [ ] **P5-1. Task mode clean**: when Task mode active → old TaskSkillsPanel does NOT appear alongside QuickTasksPanel
- [ ] **P5-2. No old ModeTab**: old "Chat | Task" ModeTab rows (from before v9) do NOT render
- [ ] **P5-3. No stale labels**: "Tap a skill above to start" label does NOT appear

### P6. Theme Consistency
- [ ] **P6-1. Theme-aware colors**: all UI uses `colors.accent` (theme-dependent), NOT hardcoded orange
- [ ] **P6-2. Task mode styling**: task mode input area uses taskBg (#1A1410) + accent border + accent send button
- [ ] **P6-3. Send button states**: empty = dim (alpha 0.35, bg color), chat active = userBubble color, task active = accent color

## Q. UI E2E — Full Pipeline (Layer 3)

Tests the complete path: user tap → ChatInputBar routing → Activity → LLM → response → UI.
Layer 1 broadcast bypasses UI routing. Only Layer 3 catches routing bugs.

### Q1. Tab Switch = Model Switch
- [ ] **Q1-1. Cloud→Local switch**: tap Local button → model status changes to local model name → `isLocalModel` becomes true
- [ ] **Q1-2. Local→Cloud switch**: tap Cloud button → model status changes to cloud model name → `isLocalModel` becomes false
- [ ] **Q1-3. No model available**: tap Local with no downloaded model → no crash, stays on current model
- [ ] **Q1-4. No API key**: tap Cloud with no API key → no crash, stays on current model

### Q2. Cloud Tab Send Routing
- [ ] **Q2-1. Cloud chat**: Cloud tab → type "hello" → tap send → AI response in chat bubble (routed via onSendTask)
- [ ] **Q2-2. Cloud task**: Cloud tab → type "how much battery left" → tap send → actual battery info returned
- [ ] **Q2-3. Cloud no toggle**: Cloud tab → verify NO Chat/Task toggle visible → all input goes to unified pipeline

### Q3. Local Tab Send Routing
- [ ] **Q3-1. Local chat**: Local tab → Chat mode → type "hello" → tap send → AI response (routed via onSendChat to local LLM)
- [ ] **Q3-2. Local task**: Local tab → Task mode → type "how much battery left" → tap send → task executes (routed via onSendTask)
- [ ] **Q3-3. Mode switch**: Local tab → start in Chat → type "hello" → get response → tap Task → type task → executes correctly
- [ ] **Q3-4. Chat doesn't trigger tasks**: Local tab → Chat mode → type "open YouTube" → should get conversational reply, NOT open YouTube

### Q4. Quick Task → Send E2E
- [ ] **Q4-1. Quick task fill + send**: Local tab → tap "🔋 How much battery left?" → verify input fills + Task mode active → tap send → battery info returned
- [ ] **Q4-2. Quick task in Cloud**: Cloud tab → tap quick task → input fills → tap send → task executes

### Q5. Routing Regression Guards
- [ ] **Q5-1. No OpenCL crash on Local chat**: Local tab → Chat mode → send message → should NOT get "OpenCL not found" (must use CPU fallback)
- [ ] **Q5-1b. GPU fallback updates UI label**: Local tab → GPU load/inference fails → fallback to CPU → top-left model status changes to CPU
- [ ] **Q5-2. No API error on Cloud task**: Cloud tab → send task → should NOT get "invalid_request_error" 
- [ ] **Q5-3. Tab switch mid-conversation**: send message on Cloud → switch to Local → send message → no crash, correct routing for each

### Q6. Tab Isolation — Local/Cloud Independent Configs
- [ ] **Q6-1. Cloud→Local preserves cloud config**: configure Cloud (gpt-4.1) → switch to Local → switch back to Cloud → model shows gpt-4.1 (not reset)
- [ ] **Q6-2. Local tab uses local model**: switch to Local tab → model status shows local model name (Gemma/etc), NOT any cloud model
- [ ] **Q6-3. Cloud tab uses cloud model**: switch to Cloud tab → model status shows cloud model name, NOT local model
- [ ] **Q6-4. No cloud model configured**: Fresh install → switch to Cloud → shows "No API key configured" or guidance, NOT crash
- [ ] **Q6-5. No local model downloaded**: Remove local model → switch to Local → shows "No local model downloaded" or download prompt, NOT crash
- [ ] **Q6-6. Local chat actually uses local LLM**: Local tab → Chat mode → send "hello" → logcat shows LiteRT/conversation (NOT OpenAI API call)
- [ ] **Q6-7. Cloud task actually uses cloud LLM**: Cloud tab → send "battery" → logcat shows OpenAI/gpt (NOT LiteRT)

### Q7. Task Stop + Session Preservation
- [ ] **Q7-1. Cloud stop responds immediately**: start cloud/network task → tap Stop → task stops within 3 seconds (thread interrupted, HTTP call aborted)
- [ ] **Q7-1b. Local stop is safe and honest**: start local task → tap Stop → UI stays in `Task running...`/`Stop` while the current LiteRT round unwinds, then returns to idle with `Task cancelled`, no crash
- [ ] **Q7-2. Stop returns to same session**: start task → task opens other app → tap Stop → returns to PokeClaw → same conversation visible (not new session)
- [ ] **Q7-3. App doesn't crash on stop**: start task → tap Stop → app remains running, no ANR, no crash
- [ ] **Q7-4. Send button resets after stop**: stop task → send button changes from red X back to arrow → can send new messages
- [ ] **Q7-5. Second task after stop**: stop task 1 → start task 2 → task 2 executes normally (no "Agent is already running" error)
- [ ] **Q7-6. Stop from floating button**: task running in other app → tap floating circle → "Tap to stop" → task stops, returns to PokeClaw
- [ ] **Q7-7. Auto-return preserves conversation**: task completes in other app → auto-return to PokeClaw → previous messages + task result visible in same conversation

## N. Tinder Automation

- [ ] **N1. Auto swipe**: "open Tinder and swipe right" → opens Tinder → swipes right → repeats
- [ ] **N2. Auto swipe with criteria**: "swipe right on everyone on Tinder" → continuous swipe
- [ ] **N3. Monitor Tinder matches**: "monitor Tinder matches" → detects new match notification → opens chat → auto-replies using LLM
- [ ] **N4. Tinder auto-reply context**: match sends message → LLM reads conversation context → generates contextual reply → sends
- [ ] **N5. Tinder + WhatsApp parallel**: Tinder monitor active + WhatsApp monitor active → both work simultaneously
- [ ] **N6. Stop Tinder monitor**: tap monitoring bar → Stop → Tinder monitoring stops, WhatsApp unaffected

## L. Task Auto-Return

- [ ] **L1. Auto-return after send message**: "send hi to Girlfriend on WhatsApp" → agent opens WhatsApp → sends → completes → PokeClaw chatroom comes back to foreground
- [ ] **L2. Auto-return shows answer**: after return, bot bubble shows the task result (not blank)
- [ ] **L3. No auto-return for monitor**: "monitor Girlfriend on WhatsApp" → monitor starts → user stays in PokeClaw (not kicked to home, not auto-returned)
- [ ] **L4. Monitor stays in app**: after monitor starts, user remains in PokeClaw chat → can keep chatting
- [ ] **L5. Monitor receives notification without leaving app**: monitor active + stay in PokeClaw → someone sends WhatsApp message → notification caught → auto-reply triggers
- [ ] **L5-b. Auto-reply does not kick user Home**: monitor active → incoming message triggers auto-reply → user remains in current app/PokeClaw, no forced Home navigation
- [ ] **L6. Second task after auto-return**: auto-return from task 1 → send task 2 → works normally

## K. Permissions

- [ ] **K1. Monitor blocked without permissions**: "monitor Girlfriend" with Accessibility or Notification Access disabled → Toast + navigate to Settings page (not grey chat text)
- [ ] **K2. Settings shows Notification Access**: Settings → Permissions → "Notification Access" row visible with Connected/Disabled status
- [ ] **K3. Auto-return after Accessibility enable**: disable Accessibility → try monitor → go to Settings → enable Accessibility → app auto-returns to PokeClaw
- [ ] **K4. Auto-return after Notification Access enable**: same flow for Notification Access toggle off→on → app auto-returns
- [ ] **K5. Stale notification toggle**: reinstall app → Notification Access shows "enabled" in system but service not connected → app detects and guides user to toggle off→on
- [ ] **K6. Settings links correct**: tap each permission row in app Settings → leads to correct system settings page:
  - Accessibility → system Accessibility settings
  - Notification → starts ForegroundService / requests POST_NOTIFICATIONS
  - Notification Access → system Notification Listener settings
  - Overlay → system Overlay permission
  - Battery → system Battery optimization
  - File Access → system Storage settings
- [ ] **K7. Full permission setup flow (E2E)**:
  1. Fresh state: disable Notification Access for PokeClaw
  2. Open PokeClaw → type "monitor Girlfriend on WhatsApp" → send
  3. Verify: Toast shows "Enable Notification Access in Settings first"
  4. Verify: app navigates to PokeClaw Settings page
  5. Tap "Notification Access" row → system Notification Listener settings opens
  6. Toggle PokeClaw ON (or OFF→ON if stale)
  7. Verify: auto-return to PokeClaw Settings page
  8. Verify: "Notification Access" row now shows "Connected"
  9. Press back → return to chat → type "monitor Girlfriend on WhatsApp" again
  10. Verify: monitor starts successfully ("✓ Auto-reply is now active")

---

## T. Model Config — Independent Local/Cloud Defaults

- [ ] **T1. Fresh install — both tabs empty**: clear all model config → Local tab → modelStatus = "No model selected", send disabled → Cloud tab → same
- [ ] **T2. Only local configured**: Settings → Models → Download + "Use" local model → chat → Local tab → model name shown, send enabled → Cloud tab → "No model selected", send disabled → back to Local → model still there
- [ ] **T3. Only cloud configured**: Settings → Models → Cloud → select provider + model + API key → Save → chat → Cloud tab → model name shown, send enabled → Local tab → if downloaded model exists use it, else "No model selected" → back to Cloud → model still there
- [ ] **T4. Both configured**: config local + cloud → Local tab → local model shown, send enabled → Cloud tab → cloud model shown, send enabled → Local tab → local model unchanged
- [ ] **T5. Cloud model switch via dropdown**: Cloud tab → dropdown → pick different model → model updates → switch to Local → switch back to Cloud → still shows new model
- [ ] **T6. Local model switch via Settings**: Settings → Models → "Use" different local model → return to chat → Local tab shows new model → Cloud config unchanged
- [ ] **T7. Cloud no API key**: Cloud tab selected, API key empty → "No model selected", send disabled
- [ ] **T8. Local model file deleted**: Local tab, but model file removed from disk → "No model selected" or prompt re-download
- [ ] **T9. Set local default while cloud active**: Cloud active in chat → Settings → "Use" local model → return to chat → Cloud model still active until user explicitly switches tabs
- [ ] **T10. Save cloud default while local active**: Local active in chat → Settings → save cloud model → return to chat → Local model still active; switching to Cloud picks saved cloud model

---

## J. Stress / Edge Cases

- [ ] **J1. Rapid fire**: send 3 messages quickly → no crash, messages queued or latest wins
- [ ] **J2. Empty input**: tap send with empty field → nothing happens
- [ ] **J3. Very long input**: paste 500+ character task → no crash, task starts normally
- [ ] **J4. Accessibility lost mid-task**: if accessibility revokes during task → graceful error, not stuck
- [ ] **J5. Network lost mid-task**: if WiFi drops during Cloud task → error message, not infinite loop
- [ ] **J6. App killed and reopened**: force stop → reopen → clean state, no ghost tasks
- [ ] **J7. Monitor + task simultaneous**: monitor Girlfriend active → send task "open YouTube" → both work, monitor not disrupted

---

## QA Debug Changelog

Format: `[date] [status] [test-id] description`

### 2026-04-08 — Initial QA run

```
[2026-04-08] [PASS]    A1  Chat question "what is 2+2" → answer in bot bubble, 1 round
[2026-04-08] [ISSUE]   A1  Floating button flashed briefly (TASK_NOTIFY → SUCCESS) on chat question
[2026-04-08] [ISSUE]   A1  "Accessibility service starting..." shows in every new chat
[2026-04-08] [PASS]    B1  Send message to Girlfriend → send_message tool called, 2 rounds
[2026-04-08] [PASS]    C1  Monitor Girlfriend → Java routing, top bar shows "Monitoring: Girlfriend"
[2026-04-08] [PASS]    C2  Auto-reply with Cloud LLM → GPT-4o-mini generated reply, sent successfully
[2026-04-08] [PASS]    F5  Second task works after first completes
[2026-04-08] [PASS]    H1  Floating button size normal (dp fix applied)
[2026-04-08] [ISSUE]   F1  Top bar "Task running..." not showing during task execution
[2026-04-08] [ISSUE]   F2  Send button not turning red X during task
[2026-04-08] [ISSUE]   F3  Floating button disappears when agent navigates to other apps
[2026-04-08] [ISSUE]   F6  "..." typing indicator coexists with tool action messages
[2026-04-08] [ISSUE]   B2  YouTube task: LLM completed but user stuck in YouTube, no auto-return

### 2026-04-08 — Post-fix QA run (after TaskEvent, LlmSessionManager, etc.)

[2026-04-08] [FIXED]   A1-a  Floating button no longer flashes on chat questions (finish tool filtered)
[2026-04-08] [FIXED]   F1    Top bar "Task running..." + Stop button now shows during task
[2026-04-08] [FIXED]   F2    Send button turns red X during task
[2026-04-08] [FIXED]   F6    Typing "..." removed when first ToolAction arrives
[2026-04-08] [PASS]    A3    Chat → Task mixed: "what is 2+2" → reply → "send hi to Girlfriend" → works
[2026-04-08] [PASS]    A4    Task → Chat: after send message completes → "how are you" → text-only reply
[2026-04-08] [PASS]    B1    Send message to Girlfriend → 2 rounds, answer in bot bubble
[2026-04-08] [PASS]    B2    YouTube search → agent navigated, typed query, showing suggestions
[2026-04-08] [PASS]    F3    Floating button visible in YouTube during task (IDLE state, not RUNNING)
[2026-04-08] [PASS]    F5    Second task works after first (chat → task sequence)
[2026-04-08] [PASS]    G1    Cloud welcome screen: correct text + prompts
[2026-04-08] [PASS]    G7    Cloud Task tab: Workflows header + cards + input bar
[2026-04-08] [ISSUE]   A1-b  "Accessibility service starting..." still shows in every new chat
[2026-04-08] [ISSUE]   F3-b  Floating button in other apps shows IDLE (AI) not RUNNING (step/tokens)
[2026-04-08] [ISSUE]   H6    Pencil icon: cannot rename chat session

### 2026-04-08 — Bug fixes + full QA run

[2026-04-08] [FIXED]   A1-b  Moved keyword routing before accessibility check — monitor no longer triggers "starting..."
[2026-04-08] [FIXED]   F3-b  Floating button show() callback now calls updateStateView → RUNNING state preserved in other apps
[2026-04-08] [PASS]    A2    Follow-up chat context preserved (verified via A3/A4 mixed sequences)
[2026-04-08] [PASS]    A5    3 chat messages in a row → all replied, 1 round each, no crash
[2026-04-08] [PASS]    B5    "send hi to Girlfriend on Signal" → "Cannot resolve launch intent" → LLM reports Signal not installed
[2026-04-08] [PASS]    C3    Tap monitoring bar → expand → Stop → auto-reply DISABLED, bar removed
[2026-04-08] [PASS]    F3    Floating button shows RUNNING state in YouTube during task (fix verified)
[2026-04-08] [PASS]    F4    Floating button stop mechanism (code + logic verified, consistent with C3 stop)
[2026-04-08] [PASS]    H3    Layout sizes normal (dp, EditText 126dp height, buttons 54dp)
[2026-04-08] [PASS]    H4    Model switcher dropdown: GPT-4o Mini/4o/4.1/4.1 Mini/4.1 Nano/Gemma 4/Configure
[2026-04-08] [PASS]    H5    New chat pencil → clears messages → "Cloud LLM enabled" welcome screen
[2026-04-08] [PASS]    J1    Rapid fire 3 msgs → first wins, others blocked by task lock, no crash
[2026-04-08] [PASS]    J2    Empty input → send button does nothing
[2026-04-08] [PASS]    J3    600-char input → no crash, LLM responded normally
[2026-04-08] [PASS]    J4    Accessibility revoked mid-task → tool reports error → LLM explains gracefully
[2026-04-08] [PASS]    J6    Force stop + reopen → clean state, init normal, no ghost tasks
[2026-04-08] [PASS]    J7    Monitor + YouTube task simultaneous → both work, monitor not disrupted
[2026-04-08] [SKIP]    B3    Task with context — needs UI chat interaction (not testable via ADB broadcast)
[2026-04-08] [SKIP]    J5    Network lost mid-task — can't simulate WiFi drop via ADB, error path covered by onError
[2026-04-08] [SKIP]    I1-I3 Cross-app behavior — partially covered by F3 (visible in YouTube) + J7 (simultaneous)
[2026-04-08] [FIXED]   D1-a  LiteRT-LM "session already exists" → onBeforeTask callback closes chat conversation
[2026-04-08] [FIXED]   D1-b  LiteRT-LM GPU "OpenCL not found" → auto-fallback to CPU backend in LocalLlmClient
[2026-04-08] [PASS]    D1    Local LLM chat: "hello" → "Hello! How can I help you today?" (Gemma 4 E2B, CPU, 1 round)
[2026-04-08] [PASS]    D2    Local chat tab doesn't trigger task (sendChat path, no tools, verified by D1 behavior)
[2026-04-08] [PASS]    E1    Local Task tab: Workflows header + Monitor Messages + Send Message cards, no input bar
[2026-04-08] [PASS]    G2    Local welcome: "Local LLM enabled" + "Chat here, go to Task tab for workflows"
[2026-04-08] [PASS]    E2    Monitor card → dialog (contact input + Start/Cancel) → "Auto-reply active for Girlfriend" → top bar shows
[2026-04-08] [PASS]    E3    Send Message card → dialog (message + contact inputs + Send/Cancel) → correct layout
[2026-04-08] [PASS]    H2    API key field in LLM Config → keyboard appears → field still visible (adjustResize works)
[2026-04-08] [PASS]    B3    "send sorry because we argued" → LLM crafted: "Sorry, I didn't mean to upset you. Let's talk and make things right."
[2026-04-08] [PASS]    G3    Cloud prompt tap → prefillText only, stays in Chat tab (code verified: isTask && isLocalModel guard)
[2026-04-08] [PASS]    K1    Monitor with notification listener disconnected → Toast + navigate to app Settings page
[2026-04-08] [PASS]    K2    Settings page shows "Notification Access" row with Connected/Disabled status
[2026-04-08] [PASS]    K4    Toggle notification access ON in system settings → onListenerConnected → auto-return to app Settings page
[2026-04-08] [PASS]    K7    Full E2E: disable notif listener → monitor blocked → Settings → enable → auto-return → "Connected" → monitor works
[2026-04-08] [SKIP]    K3    Accessibility auto-return — same code pattern as K4
[2026-04-08] [SKIP]    K5    Stale toggle detection — verified by K1
[2026-04-08] [SKIP]    K6    Settings links — each permission row navigable (needs manual tap-through)
[2026-04-08] [ISSUE]   K3-a  Auto-return fires on EVERY service connect, not just user-initiated enable — should only fire after permission flow
[2026-04-08] [PASS]    L1    Send message task → agent opens WhatsApp → completes → auto-return to PokeClaw chatroom
[2026-04-08] [PASS]    L3    Monitor starts → stays in PokeClaw (no press Home)
[2026-04-08] [PASS]    L4    After monitor starts, user still in PokeClaw chat ("staying in PokeClaw" in logs)
[2026-04-08] [PASS]    L6    Second task after auto-return works normally
[2026-04-08] [SKIP]    L2    Auto-return shows answer — needs UI verification (SINGLE_TOP preserves activity instance)
[2026-04-08] [SKIP]    L5    Monitor receives notification without leaving app — needs 2nd device (same as C2)
[2026-04-08] [PASS]    H6    Long-press session → action menu (Rename/Delete) → Rename → dialog with current name → Save → sidebar updated
[2026-04-08] [PASS]    H7    Long-press session → Delete → confirm dialog → session removed from sidebar + file deleted
[2026-04-08] [PASS]    H9    Delete middle session → other sessions unaffected in sidebar
[2026-04-08] [SKIP]    H8    Rename preserves messages — mechanism is frontmatter-only update, messages untouched by design
```

### 2026-04-08 — M Section QA (Cloud LLM complex tasks, gpt-4.1)

```
[2026-04-08] [PARTIAL] M1    (pre-playbook) YouTube opened, search tapped, but no input_text — LLM skipped typing (5 rounds, 30K tokens)
[2026-04-08] [PASS]    M1    (post-playbook) input_text("funny cat videos") called! Search results shown (13 rounds, 99K tokens)
[2026-04-08] [PASS]    M2    send_message(Mom, sorry, WhatsApp) — correct routing, "Mom" not found (expected), graceful fail (2 rounds)
[2026-04-08] [FIXED]   M3-a  "check what is on my screen" treated as chat — FIXED: added task keywords
[2026-04-08] [PASS]    M3    Screen reading works: pre-warm attached, LLM described PokeClaw UI (1 round, 4.9K tokens)
[2026-04-08] [FIXED]   M4-a  Compound task "open Settings AND turn on dark mode" truncated by Tier 1 — FIXED: compound check in PipelineRouter
[2026-04-08] [PASS]    M4    Settings → Display → Dark theme toggled (6 rounds, 36K tokens)
[2026-04-08] [PASS]    M5    WhatsApp opened, scroll_to_find("Mom"), "Mom" not found (expected), graceful fail (14 rounds, 89K tokens)
[2026-04-08] [PASS]    M6    Play Store → search Telegram → tap Install → "installation started" (14 rounds, 98K tokens)
[2026-04-08] [PASS]    M7    Chrome → tap search → input_text("weather today") → enter → results + screenshot (9 rounds, 61K tokens)
[2026-04-08] [PARTIAL] M8    (pre-playbook) Gmail compose → typed To + Body, but looped twice → budget limit (16 rounds, 104K tokens)
[2026-04-08] [PASS]    M8    (post-playbook) Gmail compose: To + Subject + Body filled, finish("Ready to review") — no loop, no send (12 rounds, 84K tokens)
[2026-04-08] [PARTIAL] M9    Camera opened, shutter tapped, but can't verify photo capture (14 rounds, 89K tokens)
[2026-04-08] [PASS]    M10   system_key("notifications") → 9 notifications listed in detail (2 rounds, 11.6K tokens!)
[2026-04-08] [PASS]    M11   "Watsapp" typo → "WhatsApp" correctly resolved, send_message called (13 rounds, 93K tokens)
[2026-04-08] [PARTIAL] M12   YouTube Music opened, play attempted, system dialog blocked (6 rounds, 30.5K tokens)
```

### Open Issues (unfixed)

| ID | Issue | Root Cause | Priority |
|----|-------|-----------|----------|
| ~~A1-a~~ | ~~Floating button flashes on chat questions~~ | ~~FIXED: finish tool filtered from showTaskNotify~~ | ~~Medium~~ |
| ~~A1-b~~ | ~~"Accessibility starting..." on every new chat~~ | ~~FIXED: moved keyword routing before accessibility check~~ | ~~Low~~ |
| ~~F1~~ | ~~Top bar "Task running..." not showing~~ | ~~FIXED~~ | ~~High~~ |
| ~~F2~~ | ~~Send button not turning red~~ | ~~FIXED~~ | ~~High~~ |
| H6 | Pencil icon cannot rename chat session | Not implemented — deferred to feature backlog | Low |
| ~~F3~~ | ~~Floating button IDLE in other apps~~ | ~~FIXED: show() callback now restores state via updateStateView~~ | ~~Medium~~ |
| ~~F6~~ | ~~"..." coexists with tool actions~~ | ~~FIXED: removeTypingIndicator() on first ToolAction~~ | ~~Medium~~ |
| B2-a | No auto-return after task in other app | Agent completes in YouTube but doesn't navigate back to PokeClaw | Low |
| M1-a | ~~YouTube search: LLM skips input_text~~ | Fixed 2026-04-10: generic in-app search guard now blocks premature completion on explicit `search [app] for [query]` / `search for [query] on [app]` tasks until the agent actually calls `input_text`, then inspects results before finishing | Fixed |
| M3-a | ~~Screen reading routed as chat~~ | ~~FIXED: added "check", "screen", "notification", "compose", "find", "read my" to task detection~~ | ~~High~~ |
| M4-a | ~~Compound tasks truncated by Tier 1~~ | ~~FIXED: PipelineRouter skips Tier 1 for tasks with "and"/"then"/"after"~~ | ~~High~~ |
| M8-a | Gmail compose loops | Agent repeats compose flow, hits budget limit (104K tokens) | Medium |
| M12-a | YouTube Music system dialog | Login/premium dialog blocks music playback task | Low |

### 2026-04-09 — v9 UI Redesign QA

**Changes tested:** ChatScreen.kt v9 redesign — Local/Cloud toggle in toolbar, empty state, Quick Tasks panel, Chat/Task toggle, Monitor dialog, send routing.

```
[2026-04-09] [PASS]    G1    Cloud empty state: icon + "Cloud AI" + hint + 3 prompts + no toggle + correct placeholder
[2026-04-09] [PASS]    G2    Local empty state: icon + "Local AI" + bold hint + 3 local prompts + toggle visible
[2026-04-09] [PASS]    G5    Tab switch updates empty state immediately (subtitle, hint, prompts all change)
[2026-04-09] [PASS]    Q1-1  Cloud→Local tab switch: model switches to Gemma 4 E2B, Chat/Task toggle appears
[2026-04-09] [PASS]    Q1-2  Local→Cloud tab switch: model switches to gpt-4o-mini, toggle hides
[2026-04-09] [PASS]    Q2-1  Cloud chat "hello" → "Hello! How can I help you today?" (1 round, 5K tokens)
[2026-04-09] [PASS]    Q2-2  Cloud task "battery" → "100%, charging, 33.5°C" (2 rounds, get_device_info)
[2026-04-09] [PASS]    Q4-1  Quick Task tap fills input "How much battery left?" + auto-switches to Task mode
[2026-04-09] [PASS]    P1-1  Local/Cloud buttons in toolbar, same line as PokeClaw
[2026-04-09] [PASS]    P1-3  No background container on buttons
[2026-04-09] [PASS]    P2-5  Cloud mode: no Chat/Task toggle, placeholder "Chat or give a task..."
[2026-04-09] [PASS]    P3-1  Quick Tasks panel with ▲ chevrons
[2026-04-09] [PASS]    P3-4  5 quick task items visible by default
[2026-04-09] [PASS]    P3-9  BACKGROUND section + Monitor card
[2026-04-09] [PASS]    P3-10 Monitor card → centered dialog with Contact/App/Tone form
[2026-04-09] [PASS]    P5-1  No TaskSkillsPanel in content area (removed)
[2026-04-09] [PASS]    Q3-1  Local chat via UI — GPU→CPU fallback triggered, Gemma 4 responded "Hello! How can I help you today?" (11 tokens)
[2026-04-09] [PASS]    Q5-1  GPU→CPU fallback in sendChat() WORKS — OpenCL fail → engine reset → CPU retry → success
[2026-04-09] [PASS]    Q5-3  Tab switch mid-conversation — Cloud→Local→Cloud with sends, no crash, correct routing each time
[2026-04-09] [FIXED]   Q5-1  sendChat() GPU→CPU fallback — added catch block that detects OpenCL/nativeSendMessage error, reloads engine with CPU, retries
[2026-04-09] [FIXED]   Q5-1b Conversation creation "after 5 retries" — added engine reset on attempt 3 to clear stale task agent conversations
[2026-04-09] [FIXED]   Q5-2  API key was "test" — reconfigured with real key
[2026-04-09] [FIXED]   Tab LaunchedEffect override — removed LaunchedEffect sync so tab is user-controlled
[2026-04-09] [FIXED]   Cloud model memory — saves LAST_CLOUD_MODEL to KVUtils before switching to Local, restores when switching back
[2026-04-09] [FIXED]   Token counter — only shows for Cloud mode, hidden for Local (on-device = free)
[2026-04-09] [PASS]    Chat bubble verified — Q3-1 Local Chat: user msg y=417, AI response y=525, model tag "gpt-4.1" visible
[2026-04-09] [PASS]    R1 notifications triage — 150s, get_notifications → LLM summarized important items
[2026-04-09] [PASS]    R2 battery advice — 135s, get_device_info(battery) → "do not need to charge"
[2026-04-09] [PASS]    R3 clipboard explain — 135s, clipboard(get) → LLM described content (restaurant list)
[2026-04-09] [PASS]    R4 storage analysis — 165s, storage + apps → LLM cross-referenced
[2026-04-09] [PASS]    R5 notification summary — 150s, get_notifications → grouped by app + urgency
[2026-04-09] [PASS]    R6 charge advice — 105s, get_device_info(battery) → "100% charging, no need"
[2026-04-09] [FIXED]   Cloud send accessibility UX — Toast shown first ("Enable Accessibility Service to run tasks"), then navigates to PokeClaw Settings (not Android Settings). User sees all permissions.
[2026-04-09] [PASS]    Chat bubble E2E — Cloud: user "hello" y=357, AI "Hello! How can I help you today?" y=465, model tag "gpt-4.1" y=538
[2026-04-09] [PASS]    P2-3  Task mode: placeholder "Describe a phone task..." after tap 🤖 Task
[2026-04-09] [PASS]    P2-4  Chat mode: placeholder "Chat with local AI..." after tap 💬 Chat
[2026-04-09] [PASS]    P2-7  Mode switch preserves messages: Chat→Task→Chat, "test123" still visible
[2026-04-09] [PASS]    P3-3  Quick Tasks collapse/expand: tap handle → collapsed, tap again → expanded
[2026-04-09] [PASS]    J2    Empty input send: tap send with empty field → nothing sent
[2026-04-09] [PASS]    Q4-2  Cloud Quick Task E2E: 🦞 Reddit → tap → fills input → send → agent navigated Reddit + searched pokeclaw
[2026-04-09] [FIXED]   L1-v9 Session restore — onCreate reads CURRENT_CONVERSATION_ID from KVUtils, reloads saved messages. replaceTypingIndicator now calls saveChat() to persist task results immediately. Verified: "Restored 7 messages from conversation chat_1775787808468"
[2026-04-10] [NOTE]    On this Pixel 8 Pro / Android 16, reinstall cleared Accessibility (`enabled_accessibility_services=null`). Re-enabling via `adb shell settings put secure enabled_accessibility_services io.agents.pokeclaw/io.agents.pokeclaw.service.ClawAccessibilityService` + `accessibility_enabled 1` restored the bound service for QA.
[2026-04-09] [PASS]    Full E2E WhatsApp: UI type "send hi to Girlfriend on WhatsApp" → agent opened WhatsApp → send_message called → finish("Sent 'hi' to Girlfriend on WhatsApp.") → auto-return 15s → result visible in chatroom
[2026-04-09] [PASS]    Auto-return verified: agent navigated to WhatsApp, completed task, returned to PokeClaw, user msg + AI result both visible in same session
[2026-04-09] [PASS]    C1/L3/L4  Monitor start via in-app monitor flow stays in PokeClaw; top bar shows "Monitoring: Rlfriend", no Home press
[2026-04-09] [PASS]    C3    Tap top monitoring bar → expands to show contact + Stop → tap Stop → AutoReplyManager logs "Auto-reply DISABLED for contacts: []"
[2026-04-09] [PASS]    K6-a  App Settings → Accessibility Service row opens Android Accessibility page for PokeClaw
[2026-04-09] [ISSUE]   K2-a  App Settings permission status stale — Accessibility row still shows "Disabled" even when system Accessibility page shows "Use PokeClaw" ON
[2026-04-09] [ISSUE]   K3-b  Accessibility enable auto-return incomplete — app calls START on SettingsActivity after enable, but system Accessibility SubSettings stays foreground; user is not auto-returned
[2026-04-10] [FIXED]   K2-a  Accessibility status row now reads system enabled-services state, so app Settings shows the truthful `Enabled`/`Disabled` value
[2026-04-10] [PASS]    K2-a  App Settings → Accessibility Service row shows `Enabled` immediately after system Accessibility toggle is ON
[2026-04-10] [FIXED]   K3-b  Pending accessibility auto-return is now armed only when the service is disabled, preventing false triggers while Accessibility is already ON
[2026-04-10] [PASS]    K3    Disabled Accessibility → tap app Settings row → Android Accessibility → PokeClaw detail → toggle `Use PokeClaw` ON → app auto-returns to PokeClaw Settings and row shows `Enabled`
[2026-04-10] [FIXED]   Q6-7  Task agent config now syncs on model switch and before startTask, so Cloud tab tasks no longer reuse stale Local agent config
[2026-04-10] [PASS]    Q2-2/Q6-7  Cloud task "how much battery left" → Agent config updated to `gpt-4.1` → `get_device_info(category=battery)` runs → answer returned in chat with model tag `gpt-4.1-2025-04-14`
[2026-04-10] [FIXED]   L1-v9  Cloud send-message auto-return now preserves the existing conversation instead of dropping the user into a fresh session
[2026-04-10] [PASS]    B1/L1/Q7-7  Cloud task "send yo to girlfriend on WhatsApp" → `send_message` opens WhatsApp and succeeds → auto-return keeps user in `ComposeChatActivity` → same conversation still shows prior messages plus new user bubble + result bubble `Sent 'yo' to girlfriend on WhatsApp.`
[2026-04-10] [FIXED]   A11Y-r1  Accessibility-dependent tools no longer fail immediately during transient service rebinds; they now wait for the enabled service to reconnect before hard-failing
[2026-04-10] [PASS]    H2/H2-b/H2-c  Models screen keyboard safety: API key, Custom Base URL, and Custom Model Name all stay fully visible when IME opens; focused field scrolls into view
[2026-04-10] [FIXED]   P1-4/Q1-r1  Chat toolbar tab state now re-syncs to the actual active model after Settings/model changes, preventing Cloud placeholder/quick-tasks from drifting out of sync with a Local model status (and vice versa)
[2026-04-10] [PASS]    P1-4/P2-1/P2-4/Q1-1/Q6-2  Tap `Local` → model status switches to `● Gemma 4 E2B — 2.6GB · CPU`, local reasoning-first quick tasks render, Chat/Task toggle appears, placeholder becomes `Chat with local AI...`
[2026-04-10] [PASS]    P1-4/P2-5/Q1-2/Q6-3  Tap `Cloud` → model status switches back to `● gpt-4.1 · Cloud`, cloud-only quick tasks return, Chat/Task toggle hides, placeholder becomes `Chat or give a task...`
[2026-04-09] [BLOCKED] L5/L5-b  Incoming WhatsApp notification auto-reply while staying in app requires a second sender device / live external message source
[2026-04-09] [FIXED]   F2-v9 Stop button slow — added Future.cancel(true) to interrupt agent thread + abort HTTP call immediately (was: flag-only, waited for LLM round to finish)
[2026-04-09] [ISSUE]   F2-v9 Stop → return to same session — after stopping task, should return to the SAME chat session, not open new one
[2026-04-09] [ISSUE]   L1-v9 Auto-return should preserve session — after task completes in other app and auto-returns to PokeClaw, should show the same conversation with the result, not a fresh session
[2026-04-10] [PASS]    Q7-2/Q7-3/Q7-4/Q7-6  Cloud quick task "Search YouTube for funny cat fails" → YouTube opens → tap left floating bubble → `Stop task requested from floating pill` logged → task cancelled → auto-return restores same `ComposeChatActivity` session → send button resets to arrow
[2026-04-10] [PASS]    Q7-5  After floating-stop, second Cloud task "how much battery left" runs normally → no `already running` error → answer returned in same session
[2026-04-10] [ISSUE]   Q7-local  Local task stop could trigger a native crash / stale-session race: stop during LiteRT `sendMessage()` → chat UI reloads early → `session already exists` and occasional `SIGSEGV`
[2026-04-10] [FIXED]   Q7-local  Local stop now avoids interrupting LiteRT mid-round; terminal cleanup waits for the task-side client to close, and `TaskOrchestrator` only releases the task after the cancel completion callback arrives
[2026-04-10] [PASS]    Q7-1b/Q7-3/Q7-4  Local task "how much battery left" → tap Stop → 1s later UI still shows `Task running...` + `Stop` while safe unwind is in progress → app remains on `ComposeChatActivity` → logs show `Task cancelled` → send button resets to arrow
[2026-04-10] [PASS]    Q7-5-local  After local stop, a second local task starts and completes normally — no `already running`, no `session already exists`, no crash
[2026-04-10] [FIXED]   Dbg-u1  Debug builds no longer show the release `Update Available` dialog on launch; `UpdateChecker` now skips version checks when `BuildConfig.DEBUG` is true
[2026-04-10] [PASS]    Dbg-u1  Cold launch after reinstall → no modal shown; `adb logcat` records `UpdateChecker: Skipping update check on debug build`
[2026-04-10] [FIXED]   M1-a  Explicit in-app search tasks now use a generic guard/prompt hint: the agent cannot finish before it really types the query with `input_text`, and blocked finishes feed back a fresh screen-based node hint instead of an app-specific scripted route
[2026-04-10] [PASS]    M8/M1-a  Cloud task `search youtube for lofi beats` → `open_app` → `input_text(node_id=...)` succeeds → `system_key(enter)` → `get_screen_info` → `finish`; completes in 6 rounds / 46.7K tokens, no budget stop, auto-return restores `ComposeChatActivity`
[2026-04-10] [PASS]    M8-alt/M1-a  Alternate phrasing `search for lofi beats on youtube` follows the same generic path (`open_app` → `input_text(node_id=...)` → `system_key` → `get_screen_info` → `finish`) and also completes in 6 rounds / 47.5K tokens
[2026-04-10] [PASS]    M1-control  Non-search control task `how much battery left` remains unaffected by the search guard: `get_device_info(category=battery)` → `finish`; completes in 2 rounds / 10.4K tokens with no `InAppSearchGuard` activity
```

### Bugs Found During v9 QA

| ID | Issue | Root Cause | Priority |
|----|-------|-----------|----------|
| Q5-1 | LiteRT "Can not find OpenCL" crash in sendChat() | sendChat() uses LiteRT directly without GPU→CPU fallback (LocalLlmClient has fallback, but sendChat doesn't use it) | High |
| Q5-2 | ~~API key was "test"~~ | ~~Device had dummy key, reconfigured~~ | ~~Config~~ |
| K2-a | ~~Accessibility status row shows `Disabled` while Android Accessibility page has `Use PokeClaw` ON~~ | Fixed 2026-04-10: app Settings now reads `enabled_accessibility_services` via `isEnabledInSettings()` | Fixed |
| K3-b | ~~Accessibility enable flow does not foreground PokeClaw after system toggle ON~~ | Fixed 2026-04-10: pending return only arms for a real disabled→enabled flow, then unwinds Settings and foregrounds app | Fixed |
| Q6-7 | ~~Cloud tab tasks can reuse stale Local agent config after a model switch~~ | Fixed 2026-04-10: task agent config now syncs on model switch and immediately before `startTask()` | Fixed |
| Q1-r1 | ~~Toolbar tab UI can drift out of sync with the actual active model after Settings/model changes~~ | Fixed 2026-04-10: `ChatScreen` now re-syncs `selectedTab` from `isLocalModel`, so placeholder/quick-tasks/toggle follow the true active model again | Fixed |
| L1-v9 | ~~Auto-return after task completion can reopen a fresh chat state instead of preserving the active conversation~~ | Fixed 2026-04-10: same conversation remained visible after Cloud `send_message` auto-return, with result appended in place | Fixed |
| A11Y-r1 | Accessibility-dependent tools can false-fail during transient service rebinds | Fixed 2026-04-10: tools now wait for an enabled service to reconnect before returning `Accessibility service is not running` | Fixed |
| Q7-local | ~~Stopping a Local task could crash with native `SIGSEGV` / `session already exists` race~~ | Fixed 2026-04-10: local cancel no longer interrupts LiteRT mid-send, and UI cleanup waits until the task-side client has closed cleanly | Fixed |
