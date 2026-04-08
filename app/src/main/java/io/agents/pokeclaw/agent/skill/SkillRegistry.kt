// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.skill

import io.agents.pokeclaw.utils.XLog

/**
 * Registry of built-in and user-defined skills.
 * Skills are loaded at app startup and matched by trigger patterns.
 */
object SkillRegistry {

    private const val TAG = "SkillRegistry"
    private val skills = mutableMapOf<String, Skill>()

    /**
     * Register a skill. Replaces existing skill with same ID.
     */
    fun register(skill: Skill) {
        skills[skill.id] = skill
        XLog.d(TAG, "Registered skill: ${skill.id} (${skill.name})")
    }

    fun findById(id: String): Skill? = skills[id]

    fun getAll(): List<Skill> = skills.values.toList()

    fun getByCategory(category: SkillCategory): List<Skill> =
        skills.values.filter { it.category == category }

    fun getUserFacing(): List<Skill> =
        skills.values.filter { it.userFacing }

    /**
     * Find a skill that matches a task by trigger patterns.
     * Returns null if no skill matches.
     */
    fun findByTrigger(task: String): Skill? {
        val lower = task.lowercase()
        return skills.values.find { skill ->
            skill.triggerPatterns.any { pattern ->
                try {
                    val regex = pattern.lowercase()
                        .replace(Regex("\\{\\w+\\}"), "(.+)")
                    Regex(regex).containsMatchIn(lower)
                } catch (e: Exception) {
                    XLog.w(TAG, "Invalid trigger pattern: $pattern", e)
                    false
                }
            }
        }
    }

    /**
     * Get skill summaries for classifier prompt.
     * Format: "id: description"
     */
    fun getSummariesForPrompt(): List<String> =
        skills.values.map { "${it.id}: ${it.description}" }

    /**
     * Load built-in skills. Called once at app startup.
     */
    fun loadBuiltInSkills() {
        register(BuiltInSkills.searchInApp())
        register(BuiltInSkills.submitForm())
        register(BuiltInSkills.dismissPopup())
        register(BuiltInSkills.scrollAndRead())
        register(BuiltInSkills.copyScreenText())
        register(BuiltInSkills.sendWhatsApp())
        register(BuiltInSkills.navigateToTab())
        register(BuiltInSkills.openAndNavigate())
        register(BuiltInSkills.acceptPermission())
        register(BuiltInSkills.swipeGesture())
        register(BuiltInSkills.goBack())
        register(BuiltInSkills.installApp())
        register(BuiltInSkills.waitForContent())
        register(BuiltInSkills.composeEmail())
        register(BuiltInSkills.setAlarm())
        register(BuiltInSkills.createCalendarEvent())
        register(BuiltInSkills.makeCall())
        register(BuiltInSkills.sendSms())
        register(BuiltInSkills.takePhoto())
        register(BuiltInSkills.clearTextField())
        register(BuiltInSkills.readNotifications())
        register(BuiltInSkills.toggleSetting())
        XLog.i(TAG, "Loaded ${skills.size} built-in skills")
    }

    fun clear() = skills.clear()
}
