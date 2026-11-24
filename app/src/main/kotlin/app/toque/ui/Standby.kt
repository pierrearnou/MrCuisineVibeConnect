package app.toque.ui

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.toque.TimeSync
import app.toque.core.i18n.tr
import app.toque.core.machine.RootShell
import app.toque.core.machine.Snapshot
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Mise en veille : après quelques minutes sans geste et sans programme en cours, l'écran
 * s'éteint. Avec [Prefs.standbyShowClock], l'heure reste affichée (rétroéclairage minimal) ;
 * sinon le panneau est coupé via le backlight système (`lcd-backlight`, root). L'alerte bol
 * chaud rallume le panneau le temps qu'elle soit visible. Le premier geste réveille.
 *
 * `off` / `set` : fil dédié (Pierre) pour ne pas bloquer l'UI. Réveil / ensureLit : hors de
 * cette file, pour ne pas rester bloqué derrière Magisk. Un 2e cran de molette peut encore
 * être nécessaire parfois ; ensureLit le couvre.
 */
class Standby(private val a: MainActivity, private val root: FrameLayout) {
    private val main = Handler(Looper.getMainLooper())
    private var lastInputMs = SystemClock.uptimeMillis()
    private var lastWeight = Int.MIN_VALUE
    private var lastLid: Boolean? = null
    private var view: FrameLayout? = null
    private var box: LinearLayout? = null
    private var clock: TextView? = null
    private var hot: TextView? = null
    private var running = false
    private var wokeMs = 0L
    /** Veille sans horloge : panneau coupé via sysfs. */
    private var panelOff = false
    /** Numéro de la veille en cours, pour ignorer une réponse root arrivée après le réveil. */
    private var sleepId = 0

    val asleep: Boolean get() = view != null

    var onWake: (() -> Unit)? = null

    private val check = object : Runnable {
        override fun run() {
            if (shouldSleep()) sleep()
            main.postDelayed(this, 10_000)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!asleep) return
            refresh()
            move()
            if (panelOff) PanelBacklight.set(panelLevel(), force = true)
            if (asleep) main.postDelayed(this, 60_000)
        }
    }

    fun start() {
        if (running) return
        running = true
        lastInputMs = SystemClock.uptimeMillis()
        main.postDelayed(check, 10_000)
    }

    fun stop() {
        running = false
        main.removeCallbacks(check)
        wake()
    }

    fun onInput(): Boolean {
        lastInputMs = SystemClock.uptimeMillis()
        if (asleep) {
            wake()
            wokeMs = SystemClock.uptimeMillis()
            return true
        }
        PanelBacklight.ensureLit()
        return false
    }

    fun onKnob(): Boolean {
        if (onInput()) return true
        return SystemClock.uptimeMillis() - wokeMs < 800
    }

    fun poke() {
        lastInputMs = SystemClock.uptimeMillis()
        wake()
    }

    fun onSnapshot(s: Snapshot) {
        val weightMoved = lastWeight != Int.MIN_VALUE && Math.abs(s.weightG - lastWeight) >= 20
        val lidMoved = lastLid != null && lastLid != s.lidOpen
        lastWeight = s.weightG
        lastLid = s.lidOpen
        if (s.busy || weightMoved || lidMoved) poke()
        else if (asleep) refresh()
    }

    private fun shouldSleep(): Boolean {
        val minutes = a.prefs.standbyMinutes
        if (minutes <= 0 || asleep || !running) return false
        if (a.snapshot.busy || a.current?.keepAwake == true) return false
        return SystemClock.uptimeMillis() - lastInputMs >= minutes * 60_000L
    }

    private fun sleep() {
        a.hideKeyboard()
        val v = FrameLayout(a)
        v.setBackgroundColor(0xFF000000.toInt())
        v.isClickable = true
        val showClock = a.prefs.standbyShowClock
        val b = Ui.vertical(a, Gravity.CENTER_HORIZONTAL)
        val t = Ui.text(a, "", 150f, 0xFF7C828B.toInt(), Ui.THIN)
        if (showClock) b.addView(t, Ui.lp(Ui.WRAP, Ui.WRAP))
        val h = Ui.text(a, "", 22f, Ui.WARN, Ui.MEDIUM)
        b.addView(h, Ui.lp(Ui.WRAP, Ui.WRAP, t = if (showClock) 6 else 0))
        if (showClock) {
            b.addView(Ui.text(a, tr("Touchez l'écran pour reprendre"), 17f, 0xFF3E434A.toInt()), Ui.lp(Ui.WRAP, Ui.WRAP, t = 18))
        }
        v.addView(b, Ui.flp(Ui.WRAP, Ui.WRAP, Gravity.CENTER))
        root.addView(v, Ui.flp())
        view = v
        box = b
        clock = if (showClock) t else null
        hot = h
        refresh()
        v.alpha = 0f
        v.animate().alpha(1f).setDuration(800).start()
        val id = ++sleepId
        if (showClock) {
            panelOff = false
            brightness(0.02f)
        } else {
            // Sysfs d'abord (pas WindowManager) : sinon Android réécrit lcd-backlight ~10.
            panelOff = true
            PanelBacklight.off { ok ->
                if (!ok && id == sleepId && asleep && panelOff) {
                    panelOff = false
                    brightness(0.01f)
                }
            }
        }
        when (a.prefs.standbyKnob) {
            "dim" -> KnobLight.start(KnobLight.NIGHT_LEVEL)
            "off" -> KnobLight.start(0)
        }
        main.postDelayed(tick, 60_000)
    }

    private fun wake() {
        val v = view ?: return
        main.removeCallbacks(tick)
        v.animate().cancel()
        box = null
        clock = null
        hot = null
        val wasPanelOff = panelOff
        panelOff = false
        root.removeView(v)
        view = null
        if (wasPanelOff) {
            PanelBacklight.restorePanelThenKnobAndWait()
        } else {
            KnobLight.stop()
            brightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        }
        if (running) onWake?.invoke()
    }

    private fun refresh() {
        clock?.text = if (TimeSync.showClock(a)) MainActivity.now() else ""
        val temp = a.snapshot.actualTempC
        val hotNow = temp >= 50
        hot?.let {
            it.text = if (hotNow) tr("Bol chaud : %d °C", temp) else ""
            it.visibility = if (hotNow) View.VISIBLE else View.GONE
        }
        // Alerte bol chaud seulement — pas d'echo 0 à chaque snapshot.
        if (panelOff) {
            val level = panelLevel()
            if (level > 0) PanelBacklight.set(level)
        }
    }

    private fun panelLevel(): Int = if (hot?.visibility == View.VISIBLE) PanelBacklight.ALERT_LEVEL else 0

    private fun move() {
        val v = view ?: return
        val b = box ?: return
        val dx = ((v.width - b.width) / 2 - Ui.u(40)).coerceAtLeast(0)
        val dy = ((v.height - b.height) / 2 - Ui.u(40)).coerceAtLeast(0)
        b.translationX = if (dx > 0) Random.nextInt(-dx, dx + 1).toFloat() else 0f
        b.translationY = if (dy > 0) Random.nextInt(-dy, dy + 1).toFloat() else 0f
    }

    private fun brightness(value: Float) {
        val lp = a.window.attributes
        lp.screenBrightness = value
        a.window.attributes = lp
    }

    /**
     * Rétroéclairage LCD. `off` / `set` sur fil dédié (Pierre). Réveil hors file.
     */
    private object PanelBacklight {
        private const val PATH = "/sys/class/leds/lcd-backlight/brightness"
        const val ALERT_LEVEL = 24
        private val main = Handler(Looper.getMainLooper())
        private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "toque-ecran").apply { isDaemon = true } }
        private var wanted = -1
        private var saved = -1
        @Volatile private var epoch = 0

        fun off(done: (Boolean) -> Unit) {
            wanted = 0
            val my = epoch
            worker.execute {
                if (epoch != my) {
                    main.post { done(false) }
                    return@execute
                }
                val r = RootShell.run("cat $PATH && echo 0 > $PATH", 5_000)
                val n = r.out.trim().lineSequence().firstOrNull()?.trim()?.toIntOrNull()
                val ok = r.ok && n != null
                if (ok && saved < 0) saved = n!!.coerceIn(1, 255)
                if (epoch != my) {
                    RootShell.run("echo 255 > $PATH", 5_000)
                    main.post { done(false) }
                    return@execute
                }
                main.post { done(ok) }
            }
        }

        fun restorePanelThenKnobAndWait() {
            wanted = -1
            val level = if (saved > 0) saved else 255
            saved = -1
            val knob = KnobLight.isDimmed
            if (knob) KnobLight.markRestored()
            epoch++
            val done = CountDownLatch(1)
            Thread({
                try {
                    RootShell.run("echo $level > $PATH", 5_000)
                    if (knob) RootShell.run(KnobLight.restoreShell + "\ntrue", 8_000)
                    RootShell.run("echo $level > $PATH", 5_000)
                } finally {
                    done.countDown()
                }
            }, "toque-wake-panel").apply { isDaemon = true }.start()
            try {
                done.await(15, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        fun ensureLit() {
            Thread({
                RootShell.run("c=\$(cat $PATH); [ \"\$c\" = 0 ] && echo 255 > $PATH", 5_000)
            }, "toque-ensure-lit").apply { isDaemon = true }.start()
        }

        fun set(level: Int, force: Boolean = false) {
            val v = level.coerceIn(0, 255)
            if (!force && v == wanted) return
            wanted = v
            val my = epoch
            worker.execute {
                if (epoch != my || saved <= 0) return@execute
                RootShell.run("echo $v > $PATH", 5_000)
                if (epoch != my && v == 0) RootShell.run("echo 255 > $PATH", 5_000)
            }
        }
    }
}
