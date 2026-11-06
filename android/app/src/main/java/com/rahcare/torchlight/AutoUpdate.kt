package com.rahcare.torchlight

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import java.net.HttpURLConnection
import java.net.URL

/**
 * নিজে নিজে আপডেট (AutoReply-এর মতো): অ্যাপ বন্ধ থাকলেও ব্যাকগ্রাউন্ডে নতুন রিলিজ খোঁজে, পেলে নামিয়ে ইনস্টল করে।
 * Android 12+ ও "অজানা উৎস" অনুমতি থাকলে নিশ্চিত-পর্দা ছাড়াই বসে; না হলে নোটিফিকেশনে চাপলে নিশ্চিত-পর্দা খোলে।
 * কোনো ব্যানার বা বোতাম চাপতে হয় না। ডাউনলোড চলে JobScheduler-এ, তাই ব্রডকাস্টের সময়সীমায় কাটা পড়ে না।
 */
object AutoUpdate {
    private const val REPO = "alamgirabdullahgh/Torchlight"
    private const val APK = "Torchlight.apk"
    private const val BASE = 100
    private const val EVERY = 10 * 60 * 1000L
    private const val MAX_TRIES = 6
    private const val MAX_SILENT_FAILS = 3
    private const val JOB_PERIODIC = 4711
    private const val JOB_NOW = 4712

    /** অ্যাপের পর্দা এখন সামনে খোলা আছে কি না (MainActivity ঠিক করে) */
    @Volatile var fg = false
    @Volatile private var busy = false

    private class Info(val v: Int, val url: String)

    private fun sp(c: Context) = c.getSharedPreferences("autoupd_q", Context.MODE_PRIVATE)

    private fun alarmPi(c: Context) = PendingIntent.getBroadcast(
        c, JOB_PERIODIC, Intent(c, AutoUpdateAlarm::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** অ্যালার্ম (~১০ মিনিট) ও রিবুট-টেকসই পর্যায়ক্রমিক জব (~১৫ মিনিট) দুটোই বসায় */
    fun schedule(c: Context, delay: Long = EVERY) {
        try {
            c.getSystemService(AlarmManager::class.java)
                .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delay, alarmPi(c))
        } catch (e: Exception) {}
        try {
            val js = c.getSystemService(JobScheduler::class.java)
            if (js.getPendingJob(JOB_PERIODIC) == null) {
                js.schedule(
                    JobInfo.Builder(JOB_PERIODIC, ComponentName(c, AutoUpdateJob::class.java))
                        .setPeriodic(15 * 60 * 1000L)
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setPersisted(true)
                        .build()
                )
            }
        } catch (e: Exception) {}
    }

    /** এখনই একটা জব চালায় (অ্যালার্ম জাগলে ডাকা হয়) */
    fun runNow(c: Context) {
        try {
            c.getSystemService(JobScheduler::class.java).schedule(
                JobInfo.Builder(JOB_NOW, ComponentName(c, AutoUpdateJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .build()
            )
        } catch (e: Exception) {}
    }

    private fun curVer(c: Context): Int {
        val pi = c.packageManager.getPackageInfo(c.packageName, 0)
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else pi.versionCode
    }

    // "latest release" লিংক কোন ট্যাগে যায় সেটা দেখে (API নয়, তাই রেট লিমিট নেই); ট্যাগের শেষ সংখ্যাই সংস্করণ
    private fun fetch(): Info? {
        return try {
            val cn = URL("https://github.com/$REPO/releases/latest").openConnection() as HttpURLConnection
            try {
                cn.instanceFollowRedirects = false
                cn.connectTimeout = 10000
                cn.readTimeout = 10000
                val tag = (cn.getHeaderField("Location") ?: "").substringAfterLast('/')
                Info(BASE + tag.substringAfterLast('.').toInt(), "https://github.com/$REPO/releases/download/$tag/$APK")
            } finally {
                cn.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }

    /** ব্যাকগ্রাউন্ড থ্রেডে চেক চালায়; অ্যাপ ব্যাকগ্রাউন্ডে গেলে ডাকা হয় */
    fun kick(c: Context, minGapMs: Long = 0L, done: (() -> Unit)? = null) {
        val app = c.applicationContext
        Thread {
            try { check(app, minGapMs) } catch (e: Exception) {} finally { done?.invoke() }
        }.start()
    }

    private fun check(c: Context, minGapMs: Long) {
        val s = sp(c)
        if (System.currentTimeMillis() - s.getLong("t", 0L) < minGapMs) return
        val i = fetch() ?: return
        s.edit().putLong("t", System.currentTimeMillis()).apply()
        if (i.v <= curVer(c)) return

        // অ্যাপ সামনে খোলা থাকলে অপেক্ষা: ফোন তখন নীরব ইনস্টল না করে নিশ্চিত-পর্দা চায়। পর্দা ব্যাকগ্রাউন্ডে গেলেই শুরু হয়
        if (fg) return
        val tries = if (s.getInt("auto_v", 0) == i.v) s.getInt("auto_n", 0) else 0
        if (tries < MAX_TRIES && c.packageManager.canRequestPackageInstalls()) {
            val silent = Build.VERSION.SDK_INT >= 31 && s.getInt("silent_fail", 0) < MAX_SILENT_FAILS
            synchronized(this) { if (busy) return; busy = true }
            s.edit().putInt("auto_v", i.v).putInt("auto_n", tries + 1).apply()
            try {
                install(c, i.url, silent)
                return
            } catch (e: Exception) {
                // নেট ধীর/ছিঁড়ে গেলে পরের চক্রে আবার চেষ্টা হবে
            } finally {
                busy = false
            }
        }
        if (s.getInt("note_v", 0) == i.v) return
        s.edit().putInt("note_v", i.v).apply()
        notice(c, canInstall = c.packageManager.canRequestPackageInstalls())
    }

    private fun install(c: Context, url: String, silent: Boolean) {
        val inst = c.packageManager.packageInstaller
        val p = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= 31) {
            p.setRequireUserAction(
                if (silent) PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
                else PackageInstaller.SessionParams.USER_ACTION_REQUIRED
            )
        }
        val id = inst.createSession(p)
        try {
            val s = inst.openSession(id)
            val cn = URL(url).openConnection() as HttpURLConnection
            try {
                cn.connectTimeout = 15000
                cn.readTimeout = 30000
                if (cn.responseCode != 200) throw Exception("HTTP ${cn.responseCode}")
                cn.inputStream.use { ins ->
                    s.openWrite("a", 0, -1).use { out ->
                        val buf = ByteArray(32 * 1024)
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                        }
                        s.fsync(out)
                    }
                }
            } finally {
                cn.disconnect()
            }
            val pi = PendingIntent.getBroadcast(
                c, id, Intent(c, AutoUpdateResult::class.java).putExtra("silent", silent),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            sp(c).edit().putBoolean("inst_silent", silent).putBoolean("inst_pending", false).apply()
            s.commit(pi.intentSender)
        } catch (e: Exception) {
            try { inst.abandonSession(id) } catch (x: Exception) {}
            throw e
        }
    }

    fun noteSilentFail(c: Context) {
        val s = sp(c)
        s.edit().putBoolean("inst_pending", true).putInt("silent_fail", s.getInt("silent_fail", 0) + 1).apply()
    }

    fun markPending(c: Context) { sp(c).edit().putBoolean("inst_pending", true).apply() }

    private fun nm(c: Context): NotificationManager {
        val m = c.getSystemService(NotificationManager::class.java)
        m.createNotificationChannel(NotificationChannel("autoupd_q", "অ্যাপ আপডেট", NotificationManager.IMPORTANCE_LOW))
        return m
    }

    private fun appName(c: Context): String = c.applicationInfo.loadLabel(c.packageManager).toString()

    // অনুমতি না থাকলে/নিজে বসানো গেলে না: একবার নোটিফিকেশন, চাপলে অ্যাপ খোলে
    private fun notice(c: Context, canInstall: Boolean) {
        try {
            val go = Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pi = PendingIntent.getActivity(c, 21, go, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            nm(c).notify(21, Notification.Builder(c, "autoupd_q")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("${appName(c)}-এর নতুন সংস্করণ এসেছে")
                .setContentText(if (canInstall) "চাপুন — অ্যাপ খুলে আপডেট করুন" else "স্বয়ংক্রিয় আপডেটের জন্য একবার \"অজানা উৎস\" অনুমতি দিন")
                .setContentIntent(pi).setAutoCancel(true).build())
        } catch (e: Exception) {}
    }

    // নিশ্চিত করার পর্দা ব্যাকগ্রাউন্ড থেকে খোলা যায় না, তাই নোটিফিকেশনে চাপলে খোলে
    fun confirmNotice(c: Context, ui: Intent) {
        try {
            val pi = PendingIntent.getActivity(c, 22, ui, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            nm(c).notify(22, Notification.Builder(c, "autoupd_q")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("${appName(c)}-এর নতুন সংস্করণ তৈরি")
                .setContentText("চাপুন — ইনস্টল নিশ্চিত করুন")
                .setContentIntent(pi).setAutoCancel(true).build())
        } catch (e: Exception) {}
    }

    // আপডেটের পর নতুন সংস্করণ চালু হলে: নীরব ইনস্টল কাজ করেছিল কি না মিলিয়ে দেখে, পুরোনো নোটিফিকেশন সরায়
    fun afterUpdate(c: Context) {
        val s = sp(c)
        if (s.getBoolean("inst_silent", false) && !s.getBoolean("inst_pending", false)) s.edit().putInt("silent_fail", 0).apply()
        s.edit().remove("inst_silent").remove("inst_pending").apply()
        try { val m = c.getSystemService(NotificationManager::class.java); m.cancel(21); m.cancel(22) } catch (e: Exception) {}
    }

    fun fail(c: Context, msg: String) {
        try {
            nm(c).notify(23, Notification.Builder(c, "autoupd_q")
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("আপডেট হয়নি")
                .setContentText(msg)
                .setStyle(Notification.BigTextStyle().bigText(msg))
                .setAutoCancel(true).build())
        } catch (e: Exception) {}
    }
}

/** ইনস্টলারের ফল; নিশ্চিত করার পর্দা লাগলে: অ্যাপ সামনে থাকলে সরাসরি খোলে, নইলে নোটিফিকেশন */
class AutoUpdateResult : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val ui = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (i.getBooleanExtra("silent", false)) AutoUpdate.noteSilentFail(c) else AutoUpdate.markPending(c)
                if (ui != null) {
                    ui.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (AutoUpdate.fg) {
                        try { c.startActivity(ui) } catch (e: Exception) { AutoUpdate.confirmNotice(c, ui) }
                    } else AutoUpdate.confirmNotice(c, ui)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> {
                if (i.getBooleanExtra("silent", false)) AutoUpdate.noteSilentFail(c) else AutoUpdate.markPending(c)
                AutoUpdate.fail(c, i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "")
            }
        }
    }
}

/** অ্যালার্ম জাগলে নিজেকে আবার সাজায় আর কাজ জবের হাতে দেয় (ব্রডকাস্টে লম্বা ডাউনলোড নয়) */
class AutoUpdateAlarm : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        AutoUpdate.schedule(c)
        AutoUpdate.runNow(c)
    }
}

class AutoUpdateJob : JobService() {
    override fun onStartJob(p: JobParameters): Boolean {
        AutoUpdate.kick(this, 4 * 60 * 1000L) { try { jobFinished(p, false) } catch (e: Exception) {} }
        return true
    }
    override fun onStopJob(p: JobParameters): Boolean = true
}

/** রিবুট বা অ্যাপ আপডেটের পর সময়সূচি আবার চালু করে */
class AutoUpdateBoot : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == Intent.ACTION_MY_PACKAGE_REPLACED) AutoUpdate.afterUpdate(c)
        AutoUpdate.schedule(c, 60_000L)
    }
}
