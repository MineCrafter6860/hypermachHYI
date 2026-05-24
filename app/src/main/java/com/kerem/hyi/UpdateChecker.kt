package com.kerem.hyi

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Scanner

data class UpdateInfo(
    val latestVersion: String,
    val downloadUrl: String,
    val releaseNotes: String? = null
)

object UpdateChecker {
    private const val GITHUB_API_URL = "https://api.github.com/repos/MineCrafter6860/hypermachHYI/releases/latest"

    fun checkForUpdates(currentVersion: String): UpdateInfo? {
        return try {
            val url = URL(GITHUB_API_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            
            if (connection.responseCode == 200) {
                val response = Scanner(connection.inputStream).useDelimiter("\\A").next()
                val json = JSONObject(response)
                val tagName = json.getString("tag_name")
                val htmlUrl = json.getString("html_url")
                val body = json.optString("body")

                if (isNewerVersion(tagName, currentVersion)) {
                    UpdateInfo(tagName, htmlUrl, body)
                } else {
                    null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Compares two version strings semantically.
     * Strips non-numeric prefixes (like 'v').
     */
    fun isNewerVersion(latest: String, current: String): Boolean {
        val latestClean = latest.replace(Regex("[^0-9.]"), "")
        val currentClean = current.replace(Regex("[^0-9.]"), "")

        val latestParts = latestClean.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = currentClean.split(".").mapNotNull { it.toIntOrNull() }

        val maxLength = maxOf(latestParts.size, currentParts.size)
        for (i in 0 until maxLength) {
            val latestPart = latestParts.getOrElse(i) { 0 }
            val currentPart = currentParts.getOrElse(i) { 0 }
            if (latestPart > currentPart) return true
            if (latestPart < currentPart) return false
        }
        return false
    }
}
