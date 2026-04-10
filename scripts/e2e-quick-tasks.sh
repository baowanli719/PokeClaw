#!/bin/bash
set -e

PASS=0; FAIL=0; TIMEOUT=0; TOTAL=0

run() {
    local NAME="$1" TASK="$2" MAX="${3:-45}"
    TOTAL=$((TOTAL+1))
    echo ""
    echo "[$TOTAL] $NAME"
    echo "    Task: $TASK"
    
    # Clear logcat
    adb logcat -c 2>/dev/null
    sleep 1
    
    # Send
    adb shell "am broadcast -a io.agents.pokeclaw.DEBUG_TASK -p io.agents.pokeclaw --es task '$TASK'" >/dev/null 2>&1
    
    # Poll for completion
    local i=0
    while [ $i -lt $MAX ]; do
        sleep 5
        i=$((i+5))
        
        local PID=$(adb shell pidof io.agents.pokeclaw 2>/dev/null)
        if [ -z "$PID" ]; then
            echo "    [${i}s] CRASHED"
            FAIL=$((FAIL+1))
            return
        fi
        
        local COMP=$(adb logcat -d 2>/dev/null | grep "$PID" | grep "onComplete.*answer=" | tail -1)
        local ERR=$(adb logcat -d 2>/dev/null | grep "$PID" | grep "onError" | tail -1)
        local ALREADY=$(adb logcat -d 2>/dev/null | grep "$PID" | grep "already running" | tail -1)
        
        if [ -n "$ALREADY" ]; then
            echo "    [${i}s] BLOCKED — agent still running previous task"
            echo "    Waiting for previous to finish..."
            sleep 15
            i=$((i+15))
            # Retry send
            adb logcat -c 2>/dev/null
            adb shell "am broadcast -a io.agents.pokeclaw.DEBUG_TASK -p io.agents.pokeclaw --es task '$TASK'" >/dev/null 2>&1
            continue
        fi
        
        if [ -n "$COMP" ]; then
            local ANS=$(echo "$COMP" | sed 's/.*answer=Task completed: //')
            local TOOLS=$(adb logcat -d 2>/dev/null | grep "$PID" | grep "onToolCall" | sed 's/.*onToolCall: //' | tr '\n' ' ')
            echo "    [${i}s] ✓ $(echo $ANS | head -c 100)"
            echo "    Tools: $(echo $TOOLS | head -c 120)"
            PASS=$((PASS+1))
            return
        fi
        
        if [ -n "$ERR" ]; then
            echo "    [${i}s] ✗ $(echo $ERR | sed 's/.*onError: //' | head -c 100)"
            FAIL=$((FAIL+1))
            return
        fi
    done
    
    echo "    TIMEOUT (${MAX}s)"
    TIMEOUT=$((TIMEOUT+1))
}

echo "=========================================="
echo "  POKECLAW E2E — ALL QUICK TASKS (CLOUD)"
echo "  $(date)"
echo "=========================================="

# Ensure app running
adb shell am start -n io.agents.pokeclaw/.ui.splash.SplashActivity >/dev/null 2>&1
sleep 3

# --- Cloud-only (multi-step) ---
run "Reddit pokeclaw"       "Open Reddit and search for pokeclaw" 60
run "YouTube cat fails"     "Search YouTube for funny cat fails" 60
run "Install Telegram"      "Install Telegram from Play Store" 90
run "Twitter trending"      "Check whats trending on Twitter and tell me" 60
run "WhatsApp chat summary" "Check my latest WhatsApp chat and summarize it" 60
run "Copy email + Google"   "Copy the latest email subject and Google it" 60
run "Write email"           "Write an email saying I will be late today" 60

# --- Reasoning (1-2 tools + analysis) ---
run "Notifications triage"  "Check my notifications — anything important?" 30
run "Clipboard explain"     "Read my clipboard and explain what it says" 30
run "Storage analysis"      "Check my storage and apps — what can I delete?" 30
run "Notification summary"  "Read my notifications and summarize" 30
run "Battery advice"        "Check my battery and tell me if I need to charge" 30

# --- Deterministic (1 tool) ---
run "WhatsApp send"         "Send hi to Girlfriend on WhatsApp" 45
run "What apps"             "What apps do I have?" 30
run "Phone temp"            "How hot is my phone?" 20
run "Bluetooth"             "Is bluetooth on?" 20
run "Battery"               "How much battery left?" 20
run "Call Mom"              "Call Mom" 30
run "Storage"               "How much storage do I have?" 20
run "Android version"       "What Android version am I running?" 20

echo ""
echo "=========================================="
echo "  RESULTS: $PASS PASS / $FAIL FAIL / $TIMEOUT TIMEOUT / $TOTAL TOTAL"
echo "=========================================="
