package com.rahcare.torchlight

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.content.pm.PackageInstaller
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import java.net.HttpURLConnection
import java.net.URL
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** টর্চলাইট: বড় অন/অফ বাটন + স্থির / SOS / ঝলক (স্ট্রোব) মোড। ক্যামেরা পারমিশন লাগে না। */
class MainActivity : Activity() {

    private enum class Mode(val label: String) {
        STEADY("স্থির"), SOS("SOS"), STROBE("ঝলক")
    }

    private val matchParent = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrapContent = ViewGroup.LayoutParams.WRAP_CONTENT

    private lateinit var cameraManager: CameraManager
    private var cameraId: String? = null
    private val handler = Handler(Looper.getMainLooper())

    private var active = false
    private var mode = Mode.STEADY
    private var step = 0

    private lateinit var power: TextView
    private lateinit var status: TextView
    private val chips = LinkedHashMap<Mode, TextView>()

    // SOS = তিন ছোট, তিন লম্বা, তিন ছোট ঝলক
    private val sosSteps: List<Pair<Boolean, Long>> by lazy {
        val s = ArrayList<Pair<Boolean, Long>>()
        fun letter(unit: Long) {
            for (i in 0 until 3) {
                s.add(Pair(true, unit))
                s.add(Pair(false, if (i < 2) 250L else 750L))
            }
        }
        letter(250L)
        letter(750L)
        letter(250L)
        s.add(Pair(false, 1500L))
        s
    }

    private val runner = object : Runnable {
        override fun run() {
            if (!active) return
            when (mode) {
                Mode.STEADY -> setTorch(true)
                Mode.STROBE -> {
                    setTorch(step % 2 == 0)
                    step++
                    handler.postDelayed(this, 90L)
                }
                Mode.SOS -> {
                    val cur = sosSteps[step % sosSteps.size]
                    setTorch(cur.first)
                    step++
                    handler.postDelayed(this, cur.second)
                }
            }
        }
    }

    // অন্য অ্যাপ/সিস্টেম/ভলিউম বাটন টর্চ অন-অফ করলে বাটনও মিলিয়ে দেখাবে (শুধু স্থির মোডে)
    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(id: String, enabled: Boolean) {
            if (id != cameraId || mode != Mode.STEADY) return
            if (active && !enabled) {
                active = false
                refreshUi()
            } else if (!active && enabled) {
                active = true
                refreshUi()
            }
        }
    }

    private lateinit var hint: TextView
    private lateinit var ver: TextView
    private lateinit var updBtn: TextView
    private var update: Updater.Info? = null
    private var updBusy = false
    private var pendingInstall = false

    // অফলাইনে খুলে পরে নেট এলে নিজে থেকে নতুন সংস্করণ খোঁজে
    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            checkUpdate()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cameraId = try {
            findFlashCamera()
        } catch (e: Exception) {
            null
        }
        buildUi()
        if (cameraId != null) {
            cameraManager.registerTorchCallback(torchCallback, handler)
        } else {
            power.isEnabled = false
        }
        refreshUi()
        try {
            getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(netCallback)
        } catch (e: Exception) {
        }
    }

    override fun onPause() {
        super.onPause()
        AutoUpdate.fg = false; AutoUpdate.kick(this, 60_000L)
    }

    override fun onResume() {
        super.onResume()
        AutoUpdate.fg = true; AutoUpdate.schedule(this)
        // অ্যাপ তালিকা/App info থেকে ফিরলে Restricted settings-এর ধাপ শেষ: চিহ্ন মুছে একবার Accessibility পাতা আবার খুলে দেয়
        if ((stateSp().getString("info_for", "") ?: "").isNotEmpty()) {
            stateSp().edit().remove("info_for").remove("try_a11y").apply()
            if (!isKeyServiceEnabled()) startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        // "অজানা সোর্স" অনুমতি দিয়ে ফিরে এলে আপডেট নিজেই শুরু হয়
        val u = update
        if (pendingInstall && u != null && packageManager.canRequestPackageInstalls()) {
            pendingInstall = false
            install(u)
        }
        update = Updater.saved(this)
        refreshUi()
        checkUpdate()
    }

    // নতুন সংস্করণ নিজে থেকে খোঁজে (অ্যাপ খুললে/ফিরলে); কোনো বোতাম চাপতে হয় না
    private fun checkUpdate() {
        val app = applicationContext
        Thread {
            val now = Updater.checkNow(app)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                update = now
                refreshUi()
            }
        }.start()
    }

    // APK নামিয়ে PackageInstaller দিয়ে ইনস্টল করে; ফোন নিশ্চিত করার পর্দা দেখাবে
    private fun install(u: Updater.Info) {
        if (!packageManager.canRequestPackageInstalls()) {
            pendingInstall = true
            Toast.makeText(this, "পরের পর্দায় \"Allow from this source\" চালু করে ফিরে আসুন — আপডেট নিজেই শুরু হবে", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        if (updBusy) return
        updBusy = true
        updBtn.text = "ডাউনলোড হচ্ছে… ০%"
        Thread {
            val inst = packageManager.packageInstaller
            val p = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            if (Build.VERSION.SDK_INT >= 31) p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            val id = inst.createSession(p)
            try {
                val s = inst.openSession(id)
                val cn = URL(u.url).openConnection() as HttpURLConnection
                cn.connectTimeout = 15000
                cn.readTimeout = 30000
                if (cn.responseCode != 200) throw Exception("HTTP ${cn.responseCode}")
                val total = cn.contentLengthLong
                cn.inputStream.use { ins ->
                    s.openWrite("a", 0, -1).use { out ->
                        val buf = ByteArray(32 * 1024)
                        var done = 0L
                        var last = -1
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt().coerceIn(0, 100)
                                if (pct != last) {
                                    last = pct
                                    runOnUiThread { updBtn.text = "ডাউনলোড হচ্ছে… $pct%" }
                                }
                            }
                        }
                        s.fsync(out)
                    }
                }
                val pi = PendingIntent.getBroadcast(
                    this, id, Intent(this, UpdateReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
                runOnUiThread { updBtn.text = "ইনস্টল হচ্ছে…" }
                s.commit(pi.intentSender)
                // ইনস্টল সফল হলে অ্যাপ নিজেই নতুন হয়ে চালু হবে; না হলে ৬০ সেকেন্ড পর বোতাম ফিরিয়ে আনি
                handler.postDelayed({ updBusy = false; refreshUi() }, 60000L)
            } catch (e: Exception) {
                try { inst.abandonSession(id) } catch (x: Exception) {}
                runOnUiThread {
                    updBusy = false
                    refreshUi()
                    Toast.makeText(this, "আপডেট শুরু করা গেল না (${e.javaClass.simpleName}) — ইন্টারনেট দেখে আবার চেষ্টা করুন", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun stateSp() = getSharedPreferences("state", Context.MODE_PRIVATE)

    private fun isKeyServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val me = ComponentName(this, TorchKeyService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(me, ignoreCase = true) }
    }

    override fun onDestroy() {
        try {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(netCallback)
        } catch (e: Exception) {
        }
        handler.removeCallbacks(runner)
        if (cameraId != null) {
            try {
                cameraManager.setTorchMode(cameraId!!, false)
            } catch (e: Exception) {
                // ignore
            }
            cameraManager.unregisterTorchCallback(torchCallback)
        }
        super.onDestroy()
    }

    private fun findFlashCamera(): String? {
        var fallback: String? = null
        for (id in cameraManager.cameraIdList) {
            val ch = cameraManager.getCameraCharacteristics(id)
            if (ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) != true) continue
            if (ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) return id
            if (fallback == null) fallback = id
        }
        return fallback
    }

    private fun setTorch(on: Boolean) {
        val id = cameraId ?: return
        try {
            cameraManager.setTorchMode(id, on)
        } catch (e: CameraAccessException) {
            fail()
        } catch (e: IllegalArgumentException) {
            fail()
        }
    }

    private fun fail() {
        active = false
        handler.removeCallbacks(runner)
        refreshUi()
        Toast.makeText(this, "টর্চ চালু করা যায়নি — অন্য কোনো অ্যাপ ক্যামেরা ব্যবহার করছে কি?", Toast.LENGTH_LONG).show()
    }

    private fun applyState() {
        handler.removeCallbacks(runner)
        step = 0
        if (active) handler.post(runner) else setTorch(false)
        refreshUi()
    }

    private fun toggle() {
        active = !active
        applyState()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun roundRect(color: Int, radiusDp: Int): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.RECTANGLE
        d.cornerRadius = dp(radiusDp).toFloat()
        d.setColor(color)
        return d
    }

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER_HORIZONTAL
        root.setBackgroundColor(Color.parseColor("#0B0F14"))
        root.setPadding(dp(24), dp(48), dp(24), dp(32))

        val title = TextView(this)
        title.text = "টর্চলাইট"
        title.setTextColor(Color.WHITE)
        title.textSize = 26f
        title.typeface = Typeface.DEFAULT_BOLD
        title.gravity = Gravity.CENTER
        root.addView(title, LinearLayout.LayoutParams(matchParent, wrapContent))

        // বর্তমান সংস্করণ (আপডেট ঠিকমতো হলো কি না বোঝার জন্য)
        ver = TextView(this)
        ver.text = "সংস্করণ ${Updater.curVer(this) - 100}"
        ver.setTextColor(Color.parseColor("#5C6877"))
        ver.textSize = 12f
        ver.gravity = Gravity.CENTER
        root.addView(ver, LinearLayout.LayoutParams(matchParent, wrapContent))

        root.addView(View(this), LinearLayout.LayoutParams(matchParent, 0, 1f))

        power = TextView(this)
        power.textSize = 24f
        power.typeface = Typeface.DEFAULT_BOLD
        power.gravity = Gravity.CENTER
        power.isClickable = true
        power.setOnClickListener { toggle() }
        val pl = LinearLayout.LayoutParams(dp(210), dp(210))
        pl.gravity = Gravity.CENTER_HORIZONTAL
        root.addView(power, pl)

        status = TextView(this)
        status.setTextColor(Color.parseColor("#8A97A8"))
        status.textSize = 15f
        status.gravity = Gravity.CENTER
        val sl = LinearLayout.LayoutParams(matchParent, wrapContent)
        sl.topMargin = dp(24)
        root.addView(status, sl)

        root.addView(View(this), LinearLayout.LayoutParams(matchParent, 0, 1f))

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        for (m in Mode.values()) {
            val chip = TextView(this)
            chip.text = m.label
            chip.textSize = 16f
            chip.typeface = Typeface.DEFAULT_BOLD
            chip.gravity = Gravity.CENTER
            chip.setPadding(0, dp(14), 0, dp(14))
            chip.isClickable = true
            chip.setOnClickListener {
                mode = m
                applyState()
            }
            val cl = LinearLayout.LayoutParams(0, wrapContent, 1f)
            cl.leftMargin = dp(5)
            cl.rightMargin = dp(5)
            row.addView(chip, cl)
            chips[m] = chip
        }
        root.addView(row, LinearLayout.LayoutParams(matchParent, wrapContent))

        hint = TextView(this)
        hint.textSize = 14f
        hint.gravity = Gravity.CENTER
        hint.setPadding(dp(12), dp(14), dp(12), dp(14))
        hint.isClickable = true
        hint.setOnClickListener {
            if (!isKeyServiceEnabled()) {
                if (stateSp().getBoolean("try_a11y", false)) {
                    // ধাপ ২: ফোন ব্লক করে রেখেছে — অ্যাপ তালিকা খুলে দেয়; সেখান থেকে ⋮ → Allow restricted settings;
                    // ফিরে এলে অ্যাপ নিজে আবার Accessibility পাতা খুলে দেয় (onResume দেখুন)
                    stateSp().edit().putString("info_for", "a11y").apply()
                    Toast.makeText(
                        this,
                        "তালিকায় \"Torchlight\" খুঁজে চাপুন, ওপরে ডানে ⋮ চেপে \"Allow restricted settings\" দিন, তারপর ফিরে আসুন",
                        Toast.LENGTH_LONG
                    ).show()
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS))
                    } catch (e: Exception) {
                        startActivity(Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS))
                    }
                } else {
                    // ধাপ ১: প্রথম চাপে সরাসরি Accessibility সেটিংস
                    stateSp().edit().putBoolean("try_a11y", true).apply()
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    Toast.makeText(this, "\"টর্চলাইট ভলিউম বাটন\" খুঁজে চালু করুন", Toast.LENGTH_LONG).show()
                }
            }
        }
        val hl = LinearLayout.LayoutParams(matchParent, wrapContent)
        hl.topMargin = dp(16)
        root.addView(hint, hl)

        // নতুন সংস্করণ এলে শুধু তখনই এই বোতাম দেখায়
        updBtn = TextView(this)
        updBtn.textSize = 16f
        updBtn.typeface = Typeface.DEFAULT_BOLD
        updBtn.gravity = Gravity.CENTER
        updBtn.setTextColor(Color.parseColor("#1A1200"))
        updBtn.background = roundRect(Color.parseColor("#FFB703"), 14)
        updBtn.setPadding(dp(12), dp(14), dp(12), dp(14))
        updBtn.isClickable = true
        updBtn.visibility = View.GONE
        updBtn.setOnClickListener { val u = update; if (u != null && !updBusy) install(u) }
        val ul = LinearLayout.LayoutParams(matchParent, wrapContent)
        ul.topMargin = dp(12)
        root.addView(updBtn, ul)

        setContentView(root)
    }

    private fun refreshUi() {
        val sp = stateSp()
        ver.text = "সংস্করণ ${Updater.curVer(this) - 100} · বাটন-ইভেন্ট: সরাসরি ${sp.getInt("diag_a11y", 0)}, মিডিয়া ${sp.getInt("diag_media", 0)}"
        val amber = Color.parseColor("#FFB703")
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(if (active) amber else Color.parseColor("#1B2430"))
        bg.setStroke(dp(6), if (active) Color.parseColor("#FFD866") else Color.parseColor("#2A3441"))
        power.background = bg
        power.text = if (active) "চালু" else "বন্ধ"
        power.setTextColor(if (active) Color.parseColor("#1A1200") else Color.parseColor("#8A97A8"))

        status.text = when {
            cameraId == null -> "এই ডিভাইসে ফ্ল্যাশ পাওয়া যায়নি"
            active -> "টর্চ জ্বলছে — " + mode.label
            else -> "চালু করতে বাটনে চাপ দিন"
        }

        if (isKeyServiceEnabled()) {
            stateSp().edit().remove("try_a11y").apply()
            hint.text = "ভলিউম-ডাউন চেপে ধরলে টর্চ অন/অফ হবে ✓"
            hint.setTextColor(Color.parseColor("#8A97A8"))
            hint.background = null
        } else {
            hint.text = if (stateSp().getBoolean("try_a11y", false)) "Restricted settings অনুমতি দিন"
            else "ভলিউম-ডাউন চেপে ধরে টর্চ চালাতে এখানে চাপ দিয়ে ফিচারটি চালু করুন"
            hint.setTextColor(amber)
            hint.background = roundRect(Color.parseColor("#1B2430"), 12)
        }

        if (update != null) {
            updBtn.visibility = View.VISIBLE
            if (!updBusy) {
                updBtn.text = if (packageManager.canRequestPackageInstalls()) "নতুন সংস্করণ এসেছে — আপডেট করুন" else "আপডেটের অনুমতি দিন"
            }
        } else {
            updBtn.visibility = View.GONE
        }

        for ((m, chip) in chips) {
            val sel = m == mode
            chip.background = roundRect(if (sel) amber else Color.parseColor("#1B2430"), 14)
            chip.setTextColor(if (sel) Color.parseColor("#1A1200") else Color.parseColor("#C5CEDA"))
        }
    }
}
