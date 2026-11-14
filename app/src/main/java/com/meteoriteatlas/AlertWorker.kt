package com.meteoriteatlas

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** يفحص NASA/JPL دوريًا: كرات نارية قرب مدن الجزائر + كويكبات كبيرة تمر قرب الأرض. */
class AlertWorker(private val ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    companion object {
        const val CHANNEL = "dz_alerts"
        const val RADIUS_KM = 350.0
        val CITIES = listOf(
            Triple("الجزائر العاصمة", 36.75, 3.06), Triple("وهران", 35.70, -0.63), Triple("قسنطينة", 36.37, 6.61),
            Triple("عنابة", 36.90, 7.77), Triple("سطيف", 36.19, 5.41), Triple("باتنة", 35.56, 6.17),
            Triple("بجاية", 36.75, 5.06), Triple("تلمسان", 34.88, -1.32), Triple("سيدي بلعباس", 35.19, -0.63),
            Triple("بسكرة", 34.85, 5.73), Triple("ورقلة", 31.95, 5.32), Triple("غرداية", 32.49, 3.67),
            Triple("الأغواط", 33.80, 2.88), Triple("بشار", 31.62, -2.22), Triple("أدرار", 27.87, -0.29),
            Triple("تندوف", 27.67, -8.15), Triple("تمنراست", 22.79, 5.52), Triple("إليزي", 26.50, 8.48),
            Triple("عين صالح", 27.20, 2.48), Triple("الوادي", 33.37, 6.86), Triple("الجلفة", 34.67, 3.25),
            Triple("تيارت", 35.37, 1.32), Triple("مستغانم", 35.93, 0.09), Triple("جيجل", 36.82, 5.77),
            Triple("تبسة", 35.40, 8.12), Triple("النعامة", 33.27, -0.31), Triple("المنيعة", 30.58, 2.88),
            Triple("تيميمون", 29.26, 0.23), Triple("برج باجي مختار", 21.33, 0.95)
        )

        fun ensureChannel(c: Context) {
            if (Build.VERSION.SDK_INT >= 26) {
                val m = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (m.getNotificationChannel(CHANNEL) == null) {
                    val ch = NotificationChannel(CHANNEL, "تنبيهات الكرات النارية", NotificationManager.IMPORTANCE_HIGH)
                    ch.description = "كرات نارية قرب مدن الجزائر وكويكبات تمر قرب الأرض"
                    m.createNotificationChannel(ch)
                }
            }
        }

        fun distKm(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
            val r = PI / 180.0
            val a = sin((la2 - la1) * r / 2).pow(2) + cos(la1 * r) * cos(la2 * r) * sin((lo2 - lo1) * r / 2).pow(2)
            return 12742.0 * asin(sqrt(a))
        }
    }

    private val prefs by lazy { ctx.getSharedPreferences("alerts_seen", Context.MODE_PRIVATE) }
    private val seen: MutableSet<String> by lazy { HashSet(prefs.getStringSet("seen", emptySet()) ?: emptySet()) }

    override fun doWork(): Result {
        return try {
            ensureChannel(ctx)
            checkFireballs()
            checkAsteroids()
            prefs.edit().putStringSet("seen", seen.toSet()).apply()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000; c.readTimeout = 60000
        c.setRequestProperty("User-Agent", "MeteoriteAtlas-Android/1.2")
        try {
            if (c.responseCode !in 200..299) throw Exception("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally { c.disconnect() }
    }

    private fun idx(fields: JSONArray, name: String): Int {
        for (i in 0 until fields.length()) if (fields.getString(i) == name) return i
        return -1
    }

    private fun cell(row: JSONArray, i: Int): String? = if (i < 0 || i >= row.length() || row.isNull(i)) null else row.getString(i)

    private fun checkFireballs() {
        val j = JSONObject(get("https://ssd-api.jpl.nasa.gov/fireball.api?req-loc=true&limit=60&sort=-date"))
        val f = j.optJSONArray("fields") ?: return
        val d = j.optJSONArray("data") ?: return
        val iDate = idx(f, "date"); val iLat = idx(f, "lat"); val iLatD = idx(f, "lat-dir")
        val iLon = idx(f, "lon"); val iLonD = idx(f, "lon-dir"); val iImp = idx(f, "impact-e")
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val now = System.currentTimeMillis()
        for (n in 0 until d.length()) {
            val row = d.optJSONArray(n) ?: continue
            val date = cell(row, iDate) ?: continue
            val key = "fb:$date"
            if (seen.contains(key)) continue
            seen.add(key)
            var lat = cell(row, iLat)?.toDoubleOrNull() ?: continue
            var lon = cell(row, iLon)?.toDoubleOrNull() ?: continue
            if (cell(row, iLatD) == "S") lat = -lat
            if (cell(row, iLonD) == "W") lon = -lon
            val t = try { fmt.parse(date)?.time ?: 0L } catch (_: Exception) { 0L }
            if (now - t > 14L * 24 * 3600 * 1000) continue // قديم: لا ننبّه
            val near = CITIES.minByOrNull { distKm(lat, lon, it.second, it.third) } ?: continue
            val km = distKm(lat, lon, near.second, near.third)
            if (km > RADIUS_KM) continue
            val e = cell(row, iImp)?.let { "\nالطاقة التقديرية: $it كيلوطن TNT." } ?: ""
            notify(key.hashCode(), "☄ كرة نارية قرب الجزائر",
                "ذروة السطوع: $date UTC، على بعد حوالي ${km.roundToInt()} كم من ${near.first}.$e\nملاحظة: NASA تنشر الحدث بعد وقوعه، وهذا ليس إنذارًا مسبقًا.")
        }
    }

    private fun checkAsteroids() {
        val j = JSONObject(get("https://ssd-api.jpl.nasa.gov/cad.api?date-min=now&date-max=%2B30&dist-max=1LD"))
        val f = j.optJSONArray("fields") ?: return
        val d = j.optJSONArray("data") ?: return
        val iDes = idx(f, "des"); val iCd = idx(f, "cd"); val iDist = idx(f, "dist"); val iH = idx(f, "h")
        for (n in 0 until d.length()) {
            val row = d.optJSONArray(n) ?: continue
            val des = cell(row, iDes) ?: continue
            val cd = cell(row, iCd) ?: continue
            val key = "ca:$des:$cd"
            if (seen.contains(key)) continue
            val h = cell(row, iH)?.toDoubleOrNull() ?: continue
            if (h > 26.5) continue // نتجاهل الصخور الصغيرة جدًا
            seen.add(key)
            val ld = (cell(row, iDist)?.toDoubleOrNull() ?: continue) / 0.00256955
            val diam = (1329000.0 / sqrt(0.14) * 10.0.pow(-h / 5.0)).roundToInt()
            notify(key.hashCode(), "🌑 كويكب سيمر قرب الأرض",
                "الكويكب $des (قطر تقديري حوالي $diam م) سيمر يوم $cd UTC على بعد ${"%.2f".format(Locale.US, ld)} مسافة قمرية حسب المدار المحسوب. هذا إشعار عام للأرض كلها، وليس خاصًا بالجزائر.")
        }
    }

    private fun notify(id: Int, title: String, text: String) {
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).setContentIntent(open).build()
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id, n)
    }
}
