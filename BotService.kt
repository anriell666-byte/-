package com.team.clicker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.content.pm.ServiceInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class BotService : Service() {

    companion object {
        const val IDLE = 0
        const val RUNNING = 1
        const val PAUSED = 2

        @Volatile var state = IDLE
        @Volatile var instance: BotService? = null

        const val REF_W = 1080.0
        const val REF_H = 2340.0
        const val CHANNEL = "clicker"
        const val MODE_MOBS = "mobs"
        const val MODE_DAYS = "days"

        // Доли экрана для поиска, как в main.js
        val ROI = mapOf(
            "incident" to doubleArrayOf(0.60, 0.10, 1.00, 0.50),
            "attack" to doubleArrayOf(0.25, 0.55, 0.80, 0.85)
        )
        val ROI_MIN = mapOf(
            "incident" to 0.9, "attack" to 0.9, "magnifier" to 0.9,
            "search_btn" to 0.85, "deploy" to 0.85, "search_left" to 0.9
        )
        // Привязка к краю: left или center по X, всегда bottom по Y
        val ANCH_CENTER = setOf("search_btn", "deploy")

        val SLOT_X = doubleArrayOf(0.176, 0.392, 0.608)
        const val SLOT_TAP_Y = 0.88
        val ENERGY_REGION = doubleArrayOf(0.25, 0.955, 0.50, 0.035)
        val PATCH = arrayOf(
            intArrayOf(-12, -12), intArrayOf(0, -12), intArrayOf(12, -12),
            intArrayOf(-12, 0), intArrayOf(0, 0), intArrayOf(12, 0),
            intArrayOf(-12, 12), intArrayOf(0, 12), intArrayOf(12, 12)
        )
        val LABEL = mapOf(
            "magnifier" to "Лупа", "search_btn" to "Поиск", "search_left" to "Поиск",
            "attack" to "Скрещённые пистолеты", "deploy" to "Развернуть", "incident" to "Инцидент"
        )
    }

    private var alive = true
    private var screenW = 0
    private var screenH = 0
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var lastFrame: Frame? = null
    private var lastK = 1.0
    private var panelRoot: LinearLayout? = null
    private var statusView: TextView? = null
    private var modeButton: Button? = null
    private var recButton: Button? = null
    private var observer: View? = null
    private var recording = false
    private var recSteps = JSONArray()
    private var recLast = 0L
    private var recMode = MODE_MOBS
    private val ui = Handler(Looper.getMainLooper())
    private var botThread: Thread? = null
    private val logFile by lazy { File(getExternalFilesDir(null), "log.txt") }
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val prefs by lazy { getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE) }

    class Stop : RuntimeException("STOP")

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        Vision.load(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (projection != null) return START_NOT_STICKY
        val code = intent?.getIntExtra("code", 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent?.getParcelableExtra("data")
        }
        if (code == 0 || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()

        val dm = DisplayMetrics()
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        screenW = dm.widthPixels
        screenH = dm.heightPixels

        val mpm = getSystemService(MediaProjectionManager::class.java)
        projection = mpm.getMediaProjection(code, data)
        reader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)
        display = projection?.createVirtualDisplay(
            "clicker", screenW, screenH, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader?.surface, null, null
        )

        ui.post { buildPanel() }
        if (botThread == null) {
            botThread = Thread { botLoop() }.also { it.start() }
        }
        log("Запуск. Экран ${screenW}x${screenH}, режим ${currentMode()}")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        alive = false
        state = IDLE
        recording = false
        try { ui.post { removeObserver(); removePanel() } } catch (e: Exception) { }
        try { display?.release() } catch (e: Exception) { }
        try { reader?.close() } catch (e: Exception) { }
        try { projection?.stop() } catch (e: Exception) { }
        try { recognizer.close() } catch (e: Exception) { }
        instance = null
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, "Кликер", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun startForegroundCompat() {
        val notif: Notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL)
                .setContentTitle("Кликер работает")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setContentTitle("Кликер работает")
                .setSmallIcon(android.R.drawable.ic_media_play).build()
        }
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notif)
        }
    }

    // ---------------- Журнал и статус ----------------

    fun log(msg: String) {
        val line = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + "  " + msg
        Log.i("clicker", line)
        try { logFile.appendText(line + "\n") } catch (e: Exception) { }
        setStatus(msg)
    }

    private fun setStatus(text: String) {
        ui.post { statusView?.text = text }
    }

    private fun currentMode(): String = prefs.getString("mode", MODE_MOBS) ?: MODE_MOBS

    // ---------------- Кадр экрана ----------------

    @Synchronized
    fun grab(): Frame? {
        val r = reader ?: return lastFrame
        val img = r.acquireLatestImage() ?: return lastFrame
        try {
            val plane = img.planes[0]
            val bmpW = plane.rowStride / plane.pixelStride
            val bmp = Bitmap.createBitmap(bmpW, screenH, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(plane.buffer)
            val cut = if (bmpW != screenW) Bitmap.createBitmap(bmp, 0, 0, screenW, screenH) else bmp
            val px = IntArray(screenW * screenH)
            cut.getPixels(px, 0, screenW, 0, 0, screenW, screenH)
            lastFrame = Frame(screenW, screenH, px)
        } finally {
            img.close()
        }
        return lastFrame
    }

    // ---------------- Управление ----------------

    private fun gate() {
        while (state == PAUSED && alive) Thread.sleep(200)
        if (state == IDLE || !alive) throw Stop()
    }

    private fun sleepMs(ms: Long) {
        var t = 0L
        while (t < ms) {
            gate()
            val s = min(200L, ms - t)
            Thread.sleep(s)
            t += s
        }
        gate()
    }

    private fun tap(x: Int, y: Int) {
        gate()
        val jx = x + (-4..4).random()
        val jy = y + (-4..4).random()
        if (!ClickService.tap(jx, jy)) {
            log("Служба специальных возможностей не включена")
        }
    }

    private fun botLoop() {
        while (alive) {
            if (state != RUNNING) {
                Thread.sleep(200)
                continue
            }
            try {
                runSession()
            } catch (e: Stop) {
                // остановлено кнопкой
            } catch (e: Exception) {
                log("Сбой: $e")
            }
            if (state == RUNNING) state = IDLE
            setStatus("Остановлено. ▶ — запуск")
        }
    }

    private fun runSession() {
        var fails = 0
        val max = 300
        log("Сессия запущена, лимит $max циклов")
        for (c in 1..max) {
            gate()
            if (prefs.getBoolean("energyCheck", false)) {
                val f = grab()
                val e = if (f != null) readEnergy(f) else null
                val minE = prefs.getInt("energyMin", 100)
                if (e != null && e < minE) {
                    log("Энергия $e < $minE. Стоп")
                    return
                }
            }
            log("Цикл $c/$max")
            val ok = oneCycle()
            fails = if (ok) 0 else fails + 1
            if (fails >= 6) {
                log("6 неудач подряд. Стоп")
                return
            }
        }
    }

    private fun oneCycle(): Boolean {
        val mode = currentMode()
        val steps = loadRec(mode)
        if (steps != null && steps.length() > 0) {
            if (!replay(steps)) return false
            return pickSquadAndDeploy()
        }
        return if (mode == MODE_DAYS) incidentCycle() else mobsCycle()
    }

    private fun mobsCycle(): Boolean {
        if (!waitTap("magnifier", 10000)) return false
        if (!waitTap("search_btn", 10000)) return false
        if (!waitTap("attack", 15000)) return false
        return pickSquadAndDeploy()
    }

    private fun incidentCycle(): Boolean {
        if (!waitTap("incident", 6000)) return false
        if (!waitTap("search_left", 6000)) return false
        if (!waitTap("attack", 15000)) return false
        return pickSquadAndDeploy()
    }

    // ---------------- Поиск кнопок ----------------

    // Координаты левого верхнего угла кнопки на текущем экране
    private fun baseX(sg: Sig, name: String, f: Frame, k: Double): Double {
        return if (name in ANCH_CENTER) {
            f.w / 2.0 + (sg.ex - REF_W / 2) * k
        } else {
            sg.ex * k
        }
    }

    private fun baseY(sg: Sig, f: Frame, k: Double): Double {
        return f.h - (REF_H - sg.ey) * k
    }

    private fun findNamed(f: Frame, name: String): Hit? {
        val sg = Vision.sig(name) ?: return null
        val minS = ROI_MIN[name] ?: 0.9
        val base = f.w / REF_W
        for (s in doubleArrayOf(1.0, 0.9, 1.1, 0.8, 1.2)) {
            val k = base * s
            val roi = ROI[name]
            val hit = if (roi != null) {
                Vision.find(
                    f, sg,
                    (roi[0] * f.w).toInt(), (roi[1] * f.h).toInt(),
                    (roi[2] * f.w).toInt(), (roi[3] * f.h).toInt(),
                    k, minS
                )
            } else {
                val bx = baseX(sg, name, f, k)
                val by = baseY(sg, f, k)
                val zx = 0.10 * f.w
                val zy = 0.05 * f.h
                Vision.find(f, sg, (bx - zx).toInt(), (by - zy).toInt(), (bx + zx).toInt(), (by + zy).toInt(), k, minS)
            }
            if (hit != null) {
                lastK = k
                return hit
            }
        }
        return null
    }

    private fun waitTap(name: String, timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            gate()
            val f = grab()
            if (f != null) {
                val hit = findNamed(f, name)
                if (hit != null) {
                    val sg = Vision.sig(name)!!
                    tap((hit.x + sg.w * lastK / 2).toInt(), (hit.y + sg.h * lastK / 2).toInt())
                    log("+ ${LABEL[name] ?: name}")
                    return true
                }
            }
            sleepMs(300)
        }
        log("Не вижу «${LABEL[name] ?: name}», пропускаю")
        return false
    }

    // ---------------- Отряды ----------------

    private fun iconAtSlot(f: Frame, name: String, i: Int, k: Double): Boolean {
        val sg = Vision.sig(name) ?: return false
        val x = (SLOT_X[i] * f.w + (sg.ex - sg.cx) * k).roundToInt()
        val y = baseY(sg, f, k).roundToInt()
        val r = (sg.radius * k).roundToInt()
        var dy = -r
        while (dy <= r) {
            var dx = -r
            while (dx <= r) {
                if (Vision.score(f, sg, x + dx, y + dy, k, true) >= 0) return true
                dx += 2
            }
            dy += 2
        }
        return false
    }

    // Жёлтая стрелка «вернулся» на карточке: проверяем цвет
    private fun orangeAtSlot(f: Frame, i: Int, k: Double): Boolean {
        val sg = Vision.sig("return_arrow") ?: return false
        val x0 = (SLOT_X[i] * f.w + (sg.ex - sg.cx) * k).roundToInt()
        val y0 = baseY(sg, f, k).roundToInt()
        val w = (sg.w * k).roundToInt()
        val h = (sg.h * k).roundToInt()
        var n = 0
        var t = 0
        var y = y0
        while (y < y0 + h) {
            var x = x0
            while (x < x0 + w) {
                if (x in 0 until f.w && y in 0 until f.h) {
                    t++
                    val r = f.r(x, y)
                    val g = f.g(x, y)
                    val b = f.b(x, y)
                    if (r > 225 && g > 140 && g < 200 && b > 70 && b < 125) n++
                }
                x += 2
            }
            y += 2
        }
        return t > 0 && n.toDouble() / t >= 0.08
    }

    // Свободные отряды: сначала со значком сна, потом со стрелкой
    private fun slotStatus(f: Frame): List<Int> {
        val k = f.w / REF_W
        val sleepSlots = ArrayList<Int>()
        val arrowSlots = ArrayList<Int>()
        for (i in 0..2) {
            if (iconAtSlot(f, "sleep_icon", i, k)) {
                sleepSlots.add(i)
            } else if (orangeAtSlot(f, i, k) || iconAtSlot(f, "return_arrow", i, k)) {
                arrowSlots.add(i)
            }
        }
        return sleepSlots + arrowSlots
    }

    private fun pickSquadAndDeploy(): Boolean {
        sleepMs(500)
        val started = System.currentTimeMillis()
        var free: List<Int> = emptyList()
        while (true) {
            gate()
            val f = grab()
            if (f != null) free = slotStatus(f)
            if (free.isNotEmpty()) break
            if (System.currentTimeMillis() - started > 600000) return false
            log("Все отряды заняты, жду освобождения")
            sleepMs(1000)
        }
        log("Свободные отряды: " + free.joinToString { (it + 1).toString() })
        for (i in free) {
            tap((SLOT_X[i] * screenW).toInt(), (SLOT_TAP_Y * screenH).toInt())
            sleepMs(300L + (0..200).random())
            if (waitTap("deploy", 3000)) return true
        }
        return false
    }

    // ---------------- Энергия (распознавание текста) ----------------

    private fun readEnergy(f: Frame): Int? {
        return try {
            val full = Bitmap.createBitmap(f.px, f.w, f.h, Bitmap.Config.ARGB_8888)
            val x = (ENERGY_REGION[0] * f.w).toInt()
            val y = (ENERGY_REGION[1] * f.h).toInt()
            val cw = (ENERGY_REGION[2] * f.w).toInt()
            val ch = (ENERGY_REGION[3] * f.h).toInt()
            val cut = Bitmap.createBitmap(full, x, y, cw, ch)
            val big = Bitmap.createScaledBitmap(cut, cw * 2, ch * 2, true)
            val res = Tasks.await(recognizer.process(InputImage.fromBitmap(big, 0)))
            val txt = res.text.replace(Regex("\\s+"), " ")
            val m = Regex("(\\d[\\d ,.]*)\\s*/\\s*\\d+").find(txt) ?: return null
            m.groupValues[1].replace(Regex("[^\\d]"), "").toIntOrNull()
        } catch (e: Exception) {
            log("Энергия: $e")
            null
        }
    }

    // ---------------- Запись и повтор ----------------

    private fun recKey(mode: String): String = "rec_${screenW}x${screenH}_$mode"

    private fun loadRec(mode: String): JSONArray? {
        return try {
            val s = prefs.getString(recKey(mode), null) ?: return null
            JSONArray(s)
        } catch (e: Exception) {
            null
        }
    }

    private fun patchMatches(f: Frame, x: Int, y: Int, p: JSONArray): Boolean {
        var ok = 0
        for (j in PATCH.indices) {
            val xx = x + PATCH[j][0]
            val yy = y + PATCH[j][1]
            if (xx < 0 || yy < 0 || xx >= f.w || yy >= f.h) continue
            val pr = p.getInt(j * 3)
            val pg = p.getInt(j * 3 + 1)
            val pb = p.getInt(j * 3 + 2)
            if (abs(f.r(xx, yy) - pr) <= 40 && abs(f.g(xx, yy) - pg) <= 40 && abs(f.b(xx, yy) - pb) <= 40) ok++
        }
        return ok >= 6
    }

    private fun replay(steps: JSONArray): Boolean {
        for (i in 0 until steps.length()) {
            val st = steps.getJSONObject(i)
            sleepMs(min(max(st.getLong("d"), 0L), 8000L))
            val x = st.getInt("x")
            val y = st.getInt("y")
            val p = st.getJSONArray("p")
            val end = System.currentTimeMillis() + 15000
            var matched = false
            while (System.currentTimeMillis() < end) {
                gate()
                val f = grab()
                if (f != null && patchMatches(f, x, y, p)) {
                    matched = true
                    break
                }
                sleepMs(300)
            }
            if (!matched) {
                log("Повтор: шаг ${i + 1} из ${steps.length}, экран не совпал с записью")
                return false
            }
            tap(x, y)
            log("Повтор: шаг ${i + 1}/${steps.length}")
        }
        return true
    }

    private fun startRecording() {
        if (state != IDLE) {
            log("Сначала остановите бота (■), потом запись")
            return
        }
        recMode = currentMode()
        recSteps = JSONArray()
        recLast = System.currentTimeMillis()
        recording = true
        addObserver()
        log("Запись «${if (recMode == MODE_DAYS) "дней до" else "мобы"}»: нажимайте сами. ■ — сохранить")
        ui.post { recButton?.text = "■ Сохранить" }
    }

    private fun stopRecording() {
        recording = false
        removeObserver()
        ui.post { recButton?.text = "● Запись" }
        if (recSteps.length() == 0) {
            log("Запись пустая, ничего не сохранено")
            return
        }
        prefs.edit().putString(recKey(recMode), recSteps.toString()).apply()
        log("Запись сохранена: ${recSteps.length()} нажатий")
    }

    private fun inPanel(x: Int, y: Int): Boolean {
        val root = panelRoot ?: return false
        val loc = IntArray(2)
        root.getLocationOnScreen(loc)
        return x >= loc[0] && x <= loc[0] + root.width && y >= loc[1] && y <= loc[1] + root.height
    }

    private fun onRecordTouch(x: Int, y: Int) {
        if (!recording || inPanel(x, y)) return
        val now = System.currentTimeMillis()
        val f = grab()
        val patch = JSONArray()
        for (j in PATCH.indices) {
            val xx = x + PATCH[j][0]
            val yy = y + PATCH[j][1]
            if (f != null && xx >= 0 && yy >= 0 && xx < f.w && yy < f.h) {
                patch.put(f.r(xx, yy)).put(f.g(xx, yy)).put(f.b(xx, yy))
            } else {
                patch.put(0).put(0).put(0)
            }
        }
        val step = JSONObject()
            .put("d", now - recLast)
            .put("x", x)
            .put("y", y)
            .put("p", patch)
        recSteps.put(step)
        recLast = now
        setStatus("Записано нажатий: ${recSteps.length()}. ■ — сохранить")
    }

    // Невидимая полоска 1x1 px: получает касания вне себя, не перехватывая их у игры
    private fun addObserver() {
        if (observer != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val v = object : View(this) {
            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (event.action == MotionEvent.ACTION_OUTSIDE) {
                    val x = event.rawX.toInt()
                    val y = event.rawY.toInt()
                    Thread { onRecordTouch(x, y) }.start()
                }
                return false
            }
        }
        val lp = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        wm.addView(v, lp)
        observer = v
    }

    private fun removeObserver() {
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            observer?.let { wm.removeView(it) }
        } catch (e: Exception) { }
        observer = null
    }

    // ---------------- Панель управления ----------------

    private fun buildPanel() {
        if (panelRoot != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xE0202020.toInt())
            setPadding(16, 16, 16, 16)
        }
        val title = TextView(this).apply {
            text = "АВТОКЛИКЕР  (тащите за заголовок)"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
        }
        root.addView(title)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply { text = "▶"; setOnClickListener { if (state != RUNNING) { state = RUNNING; log("Запуск...") } } })
        row.addView(Button(this).apply { text = "❚❚"; setOnClickListener { if (state == RUNNING) { state = PAUSED; log("Пауза") } } })
        row.addView(Button(this).apply { text = "■"; setOnClickListener { state = IDLE; log("Остановлено") } })
        row.addView(Button(this).apply { text = "✕"; setOnClickListener { stopSelf() } })
        root.addView(row)

        modeButton = Button(this).apply {
            text = modeText()
            setOnClickListener {
                if (state != IDLE) { log("Сначала остановите бота"); return@setOnClickListener }
                val next = if (currentMode() == MODE_DAYS) MODE_MOBS else MODE_DAYS
                prefs.edit().putString("mode", next).apply()
                text = modeText()
                log("Режим: ${modeText()}")
            }
        }
        root.addView(modeButton)

        recButton = Button(this).apply {
            text = "● Запись"
            setOnClickListener {
                if (recording) stopRecording() else startRecording()
            }
        }
        root.addView(recButton)

        statusView = TextView(this).apply {
            text = "Готов. ▶ — запуск"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
        }
        root.addView(statusView)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = 40
        lp.y = 400

        var startX = 0f
        var startY = 0f
        var viewX = 0
        var viewY = 0
        title.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = ev.rawX; startY = ev.rawY
                    viewX = lp.x; viewY = lp.y
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = viewX + (ev.rawX - startX).toInt()
                    lp.y = viewY + (ev.rawY - startY).toInt()
                    wm.updateViewLayout(root, lp)
                }
            }
            true
        }
        wm.addView(root, lp)
        panelRoot = root
    }

    private fun modeText(): String =
        "Режим: " + (if (currentMode() == MODE_DAYS) "дней до" else "мобы")

    private fun removePanel() {
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            panelRoot?.let { wm.removeView(it) }
        } catch (e: Exception) { }
        panelRoot = null
    }
}
