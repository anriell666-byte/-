package com.team.clicker

import android.content.Context
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

// Кадр экрана: пиксели в формате ARGB, по строкам
class Frame(val w: Int, val h: Int, val px: IntArray) {
    // Цвет в дробной точке, билинейно (как при приведении кадра к эталону).
    // out = [r, g, b]; при выходе за кадр out[0] = -1
    fun sample(x: Double, y: Double, out: IntArray) {
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        if (x0 < 0 || y0 < 0 || x0 + 1 >= w || y0 + 1 >= h) {
            out[0] = -1
            return
        }
        val fx = x - x0
        val fy = y - y0
        if (fx < 0.001 && fy < 0.001) {
            val c = px[y0 * w + x0]
            out[0] = (c shr 16) and 255
            out[1] = (c shr 8) and 255
            out[2] = c and 255
            return
        }
        val c00 = px[y0 * w + x0]
        val c10 = px[y0 * w + x0 + 1]
        val c01 = px[(y0 + 1) * w + x0]
        val c11 = px[(y0 + 1) * w + x0 + 1]
        for (i in 0..2) {
            val sh = 16 - 8 * i
            val a = ((c00 shr sh) and 255).toDouble()
            val b = ((c10 shr sh) and 255).toDouble()
            val c = ((c01 shr sh) and 255).toDouble()
            val d = ((c11 shr sh) and 255).toDouble()
            out[i] = (a * (1 - fx) * (1 - fy) + b * fx * (1 - fy) + c * (1 - fx) * fy + d * fx * fy).roundToInt()
        }
    }

    fun r(x: Int, y: Int): Int = (px[y * w + x] shr 16) and 255
    fun g(x: Int, y: Int): Int = (px[y * w + x] shr 8) and 255
    fun b(x: Int, y: Int): Int = px[y * w + x] and 255
}

// Сигнатура кнопки: точки [dx, dy, r, g, b] относительно левого верхнего угла, на эталоне 1080 px
class Sig(
    val name: String,
    val w: Int,
    val h: Int,
    val ex: Int,
    val ey: Int,
    val cx: Int,
    val radius: Int,
    val tol: Int,
    val frac: Int,
    val pts: Array<IntArray>
)

data class Hit(val x: Int, val y: Int, val score: Double)

object Vision {
    private val sigs = HashMap<String, Sig>()
    private val buf = IntArray(3)

    fun load(ctx: Context) {
        val txt = ctx.assets.open("signatures.json").bufferedReader().readText()
        val root = JSONObject(txt)
        val keys = root.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val o = root.getJSONObject(name)
            val arr = o.getJSONArray("pts")
            val pts = Array(arr.length()) { i ->
                val p = arr.getJSONArray(i)
                intArrayOf(p.getInt(0), p.getInt(1), p.getInt(2), p.getInt(3), p.getInt(4))
            }
            sigs[name] = Sig(
                name = name,
                w = o.getInt("w"),
                h = o.getInt("h"),
                ex = o.optInt("ex", 0),
                ey = o.optInt("ey", 0),
                cx = o.optInt("cx", 0),
                radius = o.optInt("R", 20),
                tol = o.optInt("tol", 45),
                frac = o.optInt("frac", 90),
                pts = pts
            )
        }
    }

    fun sig(name: String): Sig? = sigs[name]

    // Доля совпавших точек в позиции (x, y) при масштабе k.
    // early = true: сразу возвращает -1, если совпадения заведомо не хватит (быстрый отказ).
    fun score(f: Frame, sg: Sig, x: Int, y: Int, k: Double, early: Boolean): Double {
        val n = sg.pts.size
        val need = ceil(n * sg.frac / 100.0).toInt()
        val maxBad = n - need
        var ok = 0
        var bad = 0
        for (p in sg.pts) {
            f.sample(x + p[0] * k, y + p[1] * k, buf)
            if (buf[0] >= 0 &&
                abs(buf[0] - p[2]) <= sg.tol &&
                abs(buf[1] - p[3]) <= sg.tol &&
                abs(buf[2] - p[4]) <= sg.tol
            ) {
                ok++
            } else {
                bad++
                if (early && bad > maxBad) return -1.0
            }
        }
        return ok.toDouble() / n
    }

    // Полный перебор позиций в прямоугольнике (шаг 1 px); останавливается на точном совпадении
    fun find(f: Frame, sg: Sig, x0: Int, y0: Int, x1: Int, y1: Int, k: Double, minScore: Double): Hit? {
        val bw = (sg.w * k).roundToInt()
        val bh = (sg.h * k).roundToInt()
        val ax0 = maxOf(0, x0)
        val ay0 = maxOf(0, y0)
        val ax1 = minOf(x1, f.w - bw - 1)
        val ay1 = minOf(y1, f.h - bh - 1)
        var best: Hit? = null
        for (y in ay0..ay1) {
            for (x in ax0..ax1) {
                if (score(f, sg, x, y, k, true) < 0) continue
                val s = score(f, sg, x, y, k, false)
                if (s >= minScore && (best == null || s > best.score)) {
                    best = Hit(x, y, s)
                    if (s >= 0.999) return best
                }
            }
        }
        return best
    }
}
