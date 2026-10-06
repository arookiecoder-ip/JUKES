package com.example.juke.services

import com.example.juke.models.GithubRelease

/** Stable installations stay on the stable channel; beta users can move to stable. */
internal fun selectUpdateRelease(releases: List<GithubRelease>, current: String): GithubRelease? {
    val allowPrerelease = current.contains('-')
    return releases.filter { release ->
        (allowPrerelease || (!release.isPrerelease && !release.tagName.contains('-'))) &&
            release.assets.any { it.name.endsWith(".apk", true) } &&
            isNewerVersion(current, release.tagName)
    }.reduceOrNull { best, candidate ->
        if (isNewerVersion(best.tagName, candidate.tagName)) candidate else best
    }
}

internal fun isNewerVersion(current: String, remote: String): Boolean {
    fun parts(version: String): List<Int>? {
        val numbers = version.removePrefix("v").substringBefore('-').substringBefore('+').split('.')
        return numbers.map { it.toIntOrNull() ?: return null }
    }
    val c = parts(current) ?: return false
    val r = parts(remote) ?: return false
    for (i in 0 until maxOf(c.size, r.size)) {
        val difference = r.getOrElse(i) { 0 }.compareTo(c.getOrElse(i) { 0 })
        if (difference != 0) return difference > 0
    }
    return current.contains('-') && !remote.contains('-')
}
