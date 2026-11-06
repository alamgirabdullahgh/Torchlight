package com.rahcare.torchlight

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL

// GitHub-এ নতুন রিলিজ এলো কি না দেখে। "latest release" লিংক কোন ট্যাগে যায় সেটা পড়া হয় (API নয়, তাই রেট লিমিট নেই)।
// ট্যাগ v1.0.N হলে সংস্করণ নম্বর = 100 + N (build.gradle-এর versionCode-এর সাথে মেলে)।
object Updater {
    const val REPO = "alamgirabdullahgh/Torchlight"
    private const val BASE = 100

    class Info(val v: Int, val url: String, val tag: String)

    fun curVer(c: Context): Int {
        @Suppress("DEPRECATION")
        return c.packageManager.getPackageInfo(c.packageName, 0).versionCode
    }

    private fun prefs(c: Context) = c.getSharedPreferences("update", Context.MODE_PRIVATE)

    private fun fetch(): Info {
        val cn = URL("https://github.com/$REPO/releases/latest").openConnection() as HttpURLConnection
        try {
            cn.instanceFollowRedirects = false
            cn.connectTimeout = 10000
            cn.readTimeout = 10000
            val tag = (cn.getHeaderField("Location") ?: "").substringAfterLast('/')
            val n = tag.substringAfterLast('.').toInt()
            return Info(BASE + n, "https://github.com/$REPO/releases/download/$tag/Torchlight.apk", tag)
        } finally {
            cn.disconnect()
        }
    }

    // শেষবার জানা নতুন সংস্করণ (বর্তমানের চেয়ে বড় হলে)
    fun saved(c: Context): Info? {
        val sp = prefs(c)
        val v = sp.getInt("v", 0)
        val u = sp.getString("u", null)
        val t = sp.getString("t", "") ?: ""
        return if (u != null && v > curVer(c)) Info(v, u, t) else null
    }

    // নেটওয়ার্কে চেক করে — ব্যাকগ্রাউন্ড থ্রেড থেকে ডাকতে হবে। ব্যর্থ হলে আগের জানা অবস্থাই ফেরত দেয়।
    fun checkNow(c: Context): Info? {
        try {
            val i = fetch()
            prefs(c).edit().putInt("v", i.v).putString("u", i.url).putString("t", i.tag).apply()
        } catch (e: Exception) {
        }
        return saved(c)
    }
}
