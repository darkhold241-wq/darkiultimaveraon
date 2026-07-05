package com.darki.os.data.memory

import android.content.Context
import java.text.Normalizer

class MemoryManager(context: Context) {

    private val dao = DarkiDatabase.getInstance(context).memoryDao()

    suspend fun remember(content: String): Boolean {
        val trimmed = content.trim()
        if (trimmed.isBlank()) return false
        dao.insert(MemoryEntity(content = trimmed, normalizedContent = normalize(trimmed)))
        return true
    }

    suspend fun forget(content: String): Boolean {
        val keyword = bestKeyword(content) ?: return false
        val candidate = dao.search(keyword).firstOrNull() ?: return false
        dao.delete(candidate)
        return true
    }

    suspend fun recallRelevant(query: String, limit: Int = 5): List<String> {
        val keywords = normalize(query).split(" ").filter { it.length > 3 }
        if (keywords.isEmpty()) return dao.getAll().take(limit).map { it.content }

        val found = LinkedHashSet<MemoryEntity>()
        for (word in keywords) found.addAll(dao.search(word))
        if (found.isEmpty()) return emptyList()
        return found.sortedByDescending { it.createdAt }.take(limit).map { it.content }
    }

    private fun bestKeyword(text: String): String? =
        normalize(text).split(" ").filter { it.length > 3 }.maxByOrNull { it.length }

    private fun normalize(text: String): String {
        val noAccents = Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
        return noAccents.lowercase().trim()
    }
}
