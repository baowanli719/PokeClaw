// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.skill

/**
 * 8 built-in skills that cover the most common agent failure modes.
 *
 * Each skill is a deterministic tool sequence that saves 3-10+ LLM rounds.
 * Deterministic tool sequences for common agent failure modes.
 */
object BuiltInSkills {

    fun searchInApp() = Skill(
        id = "search_in_app",
        name = "Search in App",
        description = "Type a search query and submit it. Use when the task says 'search for' or 'find' in any app.",
        category = SkillCategory.INPUT,
        estimatedStepsSaved = 5,
        parameters = listOf(
            SkillParameter("query", "string", true, "The search query to type")
        ),
        triggerPatterns = listOf(
            "search for {query}",
            "search {query}",
            "find {query}",
            "搜索 {query}",
            "搵 {query}"
        ),
        steps = listOf(
            SkillStep("get_screen_info", description = "Check for search bar"),
            SkillStep("find_and_tap", mapOf("text" to "Search"), description = "Tap search icon/bar", optional = true),
            SkillStep("input_text", mapOf("text" to "{query}"), description = "Type query"),
            SkillStep("system_key", mapOf("key" to "enter"), description = "Submit search"),
            SkillStep("wait", mapOf("duration_ms" to "2000"), description = "Wait for results"),
        ),
        fallbackGoal = "The search bar should have '{query}' typed. Just press enter or submit."
    )

    fun submitForm() = Skill(
        id = "submit_form",
        name = "Submit Form",
        description = "Find and tap the Send/Submit/Save button. Use after typing a message or filling a form.",
        category = SkillCategory.INPUT,
        estimatedStepsSaved = 4,
        parameters = emptyList(),
        triggerPatterns = listOf("submit", "send message", "press send"),
        steps = listOf(
            SkillStep("get_screen_info", description = "Look for submit button"),
            SkillStep("find_and_tap", mapOf("text" to "Send|Submit|Save|Post|Done"), description = "Tap submit button"),
            SkillStep("wait", mapOf("duration_ms" to "2000"), description = "Wait for submission"),
        ),
        fallbackGoal = "Find and tap the Send, Submit, or Save button on screen."
    )

    fun dismissPopup() = Skill(
        id = "dismiss_popup",
        name = "Dismiss Popup",
        description = "Close common popups, dialogs, and overlays. Use when a dialog blocks the task.",
        category = SkillCategory.DISMISS,
        estimatedStepsSaved = 3,
        parameters = emptyList(),
        triggerPatterns = listOf("dismiss", "close popup", "close dialog"),
        steps = listOf(
            SkillStep("get_screen_info", description = "Identify popup type"),
            SkillStep("find_and_tap", mapOf("text" to "OK"), description = "Tap OK", optional = true),
            SkillStep("find_and_tap", mapOf("text" to "Got it"), description = "Tap Got it", optional = true),
            SkillStep("find_and_tap", mapOf("text" to "Close"), description = "Tap Close", optional = true),
            SkillStep("find_and_tap", mapOf("text" to "Close app"), description = "Tap Close app", optional = true),
            SkillStep("find_and_tap", mapOf("text" to "Dismiss"), description = "Tap Dismiss", optional = true),
            SkillStep("find_and_tap", mapOf("text" to "Not now"), description = "Tap Not now", optional = true),
            SkillStep("find_and_tap", mapOf("text" to "Cancel"), description = "Tap Cancel", optional = true),
            SkillStep("find_and_tap", mapOf("text" to "Skip"), description = "Tap Skip", optional = true),
            SkillStep("find_and_tap", mapOf("text" to "Wait"), description = "Tap Wait", optional = true),
        ),
        fallbackGoal = "A popup or dialog is blocking. Find and tap the close/dismiss button."
    )

    fun scrollAndRead() = Skill(
        id = "scroll_and_read",
        name = "Scroll and Read",
        description = "Scroll down the page and collect all visible text content.",
        category = SkillCategory.GENERAL,
        estimatedStepsSaved = 10,
        parameters = listOf(
            SkillParameter("max_scrolls", "int", false, "Maximum number of scrolls", "5")
        ),
        triggerPatterns = listOf("read the page", "scroll and read", "read all content"),
        steps = listOf(
            SkillStep("get_screen_info", description = "Read current screen"),
            SkillStep("swipe", mapOf("direction" to "up"), description = "Scroll down"),
            SkillStep("get_screen_info", description = "Read after scroll"),
            SkillStep("swipe", mapOf("direction" to "up"), description = "Scroll down more"),
            SkillStep("get_screen_info", description = "Read after scroll"),
        ),
        fallbackGoal = "Scroll down the page and collect all visible text."
    )

    fun copyScreenText() = Skill(
        id = "copy_screen_text",
        name = "Copy Screen Text",
        description = "Extract all visible text from the current screen.",
        category = SkillCategory.GENERAL,
        estimatedStepsSaved = 3,
        parameters = emptyList(),
        triggerPatterns = listOf("copy text", "extract text", "read screen"),
        steps = listOf(
            SkillStep("get_screen_info", description = "Read screen content"),
        ),
        fallbackGoal = "Read and return all visible text on the screen."
    )

    fun sendWhatsApp() = Skill(
        id = "send_whatsapp",
        name = "Send WhatsApp Message",
        description = "Open WhatsApp, find a contact, and send a message.",
        category = SkillCategory.MESSAGING,
        estimatedStepsSaved = 8,
        parameters = listOf(
            SkillParameter("contact", "string", true, "Contact name to message"),
            SkillParameter("message", "string", true, "Message to send")
        ),
        triggerPatterns = listOf(
            "send .+ on whatsapp",
            "whatsapp .+ to .+",
            "message .+ on whatsapp"
        ),
        steps = listOf(
            SkillStep("open_app", mapOf("app_name" to "WhatsApp"), description = "Open WhatsApp"),
            SkillStep("wait", mapOf("duration_ms" to "2000"), description = "Wait for app"),
            SkillStep("find_and_tap", mapOf("text" to "{contact}"), description = "Find contact"),
            SkillStep("wait", mapOf("duration_ms" to "1000"), description = "Wait for chat"),
            SkillStep("input_text", mapOf("text" to "{message}"), description = "Type message"),
            SkillStep("find_and_tap", mapOf("text" to "Send"), description = "Tap send", optional = true),
            SkillStep("system_key", mapOf("key" to "enter"), description = "Submit message"),
        ),
        fallbackGoal = "In WhatsApp, find contact '{contact}' and send message: {message}"
    )

    fun navigateToTab() = Skill(
        id = "navigate_to_tab",
        name = "Navigate to Tab",
        description = "Tap a bottom navigation tab in the current app.",
        category = SkillCategory.NAVIGATION,
        estimatedStepsSaved = 3,
        parameters = listOf(
            SkillParameter("tab_name", "string", true, "Name of the tab to navigate to")
        ),
        triggerPatterns = listOf("go to .+ tab", "switch to .+ tab", "tap .+ tab"),
        steps = listOf(
            SkillStep("find_and_tap", mapOf("text" to "{tab_name}"), description = "Tap tab"),
            SkillStep("wait", mapOf("duration_ms" to "1000"), description = "Wait for tab content"),
        ),
        fallbackGoal = "Find and tap the '{tab_name}' tab at the bottom of the screen."
    )

    fun openAndNavigate() = Skill(
        id = "open_and_navigate",
        name = "Open and Navigate",
        description = "Open an app and navigate to a specific section.",
        category = SkillCategory.NAVIGATION,
        estimatedStepsSaved = 6,
        parameters = listOf(
            SkillParameter("app_name", "string", true, "App to open"),
            SkillParameter("section", "string", true, "Section to navigate to")
        ),
        triggerPatterns = listOf(
            "open .+ and go to .+",
            "open .+ and navigate to .+"
        ),
        steps = listOf(
            SkillStep("open_app", mapOf("app_name" to "{app_name}"), description = "Open app"),
            SkillStep("wait", mapOf("duration_ms" to "3000"), description = "Wait for app"),
            SkillStep("find_and_tap", mapOf("text" to "{section}"), description = "Navigate to section"),
            SkillStep("wait", mapOf("duration_ms" to "1000"), description = "Wait for content"),
        ),
        fallbackGoal = "Open {app_name} and navigate to the {section} section."
    )

    fun acceptPermission() = Skill(
        id = "accept_permission",
        name = "Accept Permission",
        description = "Accept permission dialogs, terms, or consent popups. Use when a dialog asks to Allow, Accept, or Agree.",
        category = SkillCategory.DISMISS,
        estimatedStepsSaved = 3,
        parameters = emptyList(),
        triggerPatterns = listOf("accept permission", "allow permission", "grant access"),
        steps = listOf(
            SkillStep("get_screen_info", description = "Check for permission dialog"),
            SkillStep("find_and_tap", mapOf("text" to "Allow"), description = "Tap Allow", optional = true),
            SkillStep("find_and_tap", mapOf("text" to "While using the app"), description = "Tap While using", optional = true),
            SkillStep("find_and_tap", mapOf("text" to "Accept"), description = "Tap Accept", optional = true),
            SkillStep("find_and_tap", mapOf("text" to "Agree"), description = "Tap Agree", optional = true),
            SkillStep("find_and_tap", mapOf("text" to "Continue"), description = "Tap Continue", optional = true),
            SkillStep("find_and_tap", mapOf("text" to "I agree"), description = "Tap I agree", optional = true),
            SkillStep("find_and_tap", mapOf("text" to "ALLOW"), description = "Tap ALLOW caps", optional = true),
        ),
        fallbackGoal = "A permission or consent dialog appeared. Find and tap the Allow/Accept/Agree button."
    )

    fun swipeGesture() = Skill(
        id = "swipe_gesture",
        name = "Swipe Screen",
        description = "Swipe the screen in a direction. Use for Tinder swipes, Instagram stories, carousels, or any swipeable content.",
        category = SkillCategory.NAVIGATION,
        estimatedStepsSaved = 2,
        parameters = listOf(
            SkillParameter("direction", "string", true, "Direction: left, right, up, or down")
        ),
        triggerPatterns = listOf(
            "swipe {direction}",
            "scroll {direction}"
        ),
        steps = listOf(
            SkillStep("swipe", mapOf("direction" to "{direction}"), description = "Swipe {direction}"),
            SkillStep("wait", mapOf("duration_ms" to "1000"), description = "Wait for animation"),
        ),
        fallbackGoal = "Swipe the screen {direction}."
    )

    fun goBack() = Skill(
        id = "go_back",
        name = "Go Back",
        description = "Press the back button to return to the previous screen.",
        category = SkillCategory.NAVIGATION,
        estimatedStepsSaved = 1,
        parameters = emptyList(),
        triggerPatterns = listOf("go back", "press back", "navigate back"),
        steps = listOf(
            SkillStep("system_key", mapOf("key" to "back"), description = "Press back"),
            SkillStep("wait", mapOf("duration_ms" to "1000"), description = "Wait for transition"),
        ),
        fallbackGoal = "Press the back button."
    )

    fun installApp() = Skill(
        id = "install_app",
        name = "Install App from Play Store",
        description = "Search for and install an app from Google Play Store.",
        category = SkillCategory.NAVIGATION,
        estimatedStepsSaved = 10,
        parameters = listOf(
            SkillParameter("app_name", "string", true, "Name of the app to install")
        ),
        triggerPatterns = listOf(
            "install {app_name}",
            "download {app_name}",
            "get {app_name} from play store"
        ),
        steps = listOf(
            SkillStep("open_app", mapOf("app_name" to "Play Store"), description = "Open Play Store"),
            SkillStep("wait", mapOf("duration_ms" to "3000"), description = "Wait for store"),
            SkillStep("find_and_tap", mapOf("text" to "Search"), description = "Tap search bar", optional = true),
            SkillStep("input_text", mapOf("text" to "{app_name}"), description = "Type app name"),
            SkillStep("system_key", mapOf("key" to "enter"), description = "Submit search"),
            SkillStep("wait", mapOf("duration_ms" to "3000"), description = "Wait for results"),
            SkillStep("find_and_tap", mapOf("text" to "{app_name}"), description = "Tap app from results"),
            SkillStep("wait", mapOf("duration_ms" to "2000"), description = "Wait for app page"),
            SkillStep("find_and_tap", mapOf("text" to "Install"), description = "Tap Install"),
            SkillStep("wait", mapOf("duration_ms" to "5000"), description = "Wait for install"),
        ),
        fallbackGoal = "In Play Store, search for '{app_name}' and tap Install."
    )
}
