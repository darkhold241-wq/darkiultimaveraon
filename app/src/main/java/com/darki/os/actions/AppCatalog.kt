package com.darki.os.actions

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import java.text.Normalizer

/**
 * Catalogo de apps y juegos instalados, construido automaticamente
 * (nada de configurarlos uno por uno). Se usa como ultimo recurso
 * cuando ninguna AbrirAppAction curada de BasicActions/DarkiActionRegistry
 * calzo con el comando: permite "abre COD Mobile", "abre Free Fire",
 * "abre Brawl Stars", etc. para cualquier app instalada, tolerando
 * variaciones de como Vosk transcribe el nombre.
 *
 * Es un `object` (no una clase) para encajar con el resto de
 * DarkiActionRegistry: las acciones son stateless y reciben el
 * Context en cada execute(), asi que el catalogo tambien recibe el
 * Context por metodo en vez de guardarlo en el constructor.
 */
object AppCatalog {

    data class AppEntry(
        val label: String,
        val normalizedLabel: String,
        val packageName: String
    )

    @Volatile
    private var cache: List<AppEntry> = emptyList()

    @Volatile
    private var lastRefreshMs: Long = 0L

    private const val CACHE_TTL_MS = 5 * 60 * 1000L

    fun refresh(context: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && cache.isNotEmpty() && now - lastRefreshMs < CACHE_TTL_MS) return

        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = runCatching {
            pm.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
        }.getOrDefault(emptyList())

        cache = resolved.mapNotNull { info ->
            val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
            val label = runCatching { info.loadLabel(pm).toString() }.getOrDefault(pkg)
            AppEntry(label = label, normalizedLabel = normalize(label), packageName = pkg)
        }.distinctBy { it.packageName }

        lastRefreshMs = now
    }

    fun findBestMatch(context: Context, spokenName: String, minScore: Double = 0.45): AppEntry? {
        refresh(context)
        if (cache.isEmpty()) return null

        val query = normalize(spokenName)
        if (query.isBlank()) return null

        return cache
            .map { it to similarity(query, it.normalizedLabel) }
            .filter { it.second >= minScore }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun similarity(query: String, candidate: String): Double {
        if (candidate == query) return 1.0
        if (candidate.contains(query) || query.contains(candidate)) {
            val longer = maxOf(candidate.length, query.length)
            val shorter = minOf(candidate.length, query.length)
            return 0.75 + (0.2 * shorter / longer)
        }
        val distance = levenshtein(query, candidate)
        val maxLen = maxOf(query.length, candidate.length)
        if (maxLen == 0) return 0.0
        return 1.0 - (distance.toDouble() / maxLen)
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) {
                    dp[i - 1][j - 1]
                } else {
                    1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
                }
            }
        }
        return dp[a.length][b.length]
    }

    private fun normalize(text: String): String {
        val noAccents = Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
        return noAccents.lowercase().trim()
    }
}
