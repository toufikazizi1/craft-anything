package com.meteoriteatlas

/** يُبقي فقط سجلات الجزائر من سنة 2000 فما فوق. الحدود تقريبية (مضلّع مبسّط)، فقد تتأثر نقاط قرب الحدود. */
object AlgeriaFilter {
    private val POLY: Array<DoubleArray> = arrayOf(doubleArrayOf(35.15,-2.25),doubleArrayOf(35.9,-0.9),doubleArrayOf(36.2,0.5),doubleArrayOf(36.6,1.5),doubleArrayOf(36.85,3.0),doubleArrayOf(37.0,4.0),doubleArrayOf(36.9,5.1),doubleArrayOf(37.0,6.0),doubleArrayOf(37.05,7.0),doubleArrayOf(37.1,8.0),doubleArrayOf(37.1,8.65),doubleArrayOf(36.5,8.4),doubleArrayOf(35.4,8.45),doubleArrayOf(34.4,7.8),doubleArrayOf(33.9,7.95),doubleArrayOf(33.2,8.3),doubleArrayOf(32.0,9.2),doubleArrayOf(30.25,9.5),doubleArrayOf(29.0,9.9),doubleArrayOf(27.5,10.0),doubleArrayOf(25.5,10.3),doubleArrayOf(24.0,11.5),doubleArrayOf(23.52,11.98),doubleArrayOf(21.3,8.0),doubleArrayOf(19.15,4.25),doubleArrayOf(19.4,3.0),doubleArrayOf(20.0,2.5),doubleArrayOf(21.3,0.2),doubleArrayOf(22.5,-1.0),doubleArrayOf(23.5,-3.0),doubleArrayOf(25.0,-4.8),doubleArrayOf(25.0,-8.67),doubleArrayOf(27.67,-8.67),doubleArrayOf(28.0,-8.67),doubleArrayOf(29.0,-7.5),doubleArrayOf(30.0,-5.7),doubleArrayOf(30.7,-3.9),doubleArrayOf(31.6,-2.3),doubleArrayOf(32.1,-1.2),doubleArrayOf(33.0,-1.7),doubleArrayOf(34.0,-1.8),doubleArrayOf(34.9,-1.75))

    private fun inside(lat: Double, lon: Double): Boolean {
        var c = false
        val n = POLY.size
        for (i in 0 until n) {
            val a = POLY[i]
            val b = POLY[(i + 1) % n]
            if ((a[1] > lon) != (b[1] > lon) && lat < (b[0] - a[0]) * (lon - a[1]) / (b[1] - a[1]) + a[0]) c = !c
        }
        return c
    }

    fun keep(lat: Double, lon: Double, place: String, year: String): Boolean {
        val y = Regex("(19|20)\\d{2}").find(year)?.value?.toIntOrNull() ?: return false
        if (y < 2000 || y > 2026) return false
        val p = place.lowercase()
        if (p.contains("algeria") || p.contains("algérie") || p.contains("الجزائر")) return true
        return inside(lat, lon)
    }
}
