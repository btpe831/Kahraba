package com.karaba.power

import android.content.Context
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes one line per second (time, light, wobble, charger, source) to a file per day.
 * Shared from the app so the detection limits can be tuned on real data. Keeps 7 days.
 */
class DataLogger(ctx: Context) {
    val dir = File(ctx.filesDir, "data").apply { mkdirs() }
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private var day = ""
    private var writer: BufferedWriter? = null
    private var lines = 0

    fun write(now: Long, lux: Double?, flicker: Double?, plugged: Boolean?, source: Source, status: String) {
        val d = dayFmt.format(Date(now))
        if (d != day) {
            close()
            day = d
            val f = File(dir, "light-$d.csv")
            val isNew = !f.exists()
            writer = java.io.FileWriter(f, true).buffered()
            if (isNew) writer?.write("time,lux,wobble_pct,charger,source,light_status\n")
            deleteOld(now)
        }
        val w = writer ?: return
        w.write(
            "${timeFmt.format(Date(now))},${lux?.let { "%.2f".format(Locale.US, it) } ?: ""}," +
                "${flicker?.let { "%.3f".format(Locale.US, it) } ?: ""}," +
                "${when (plugged) { true -> "on"; false -> "off"; null -> "" }},${source.name}," +
                "\"${status.replace("\"", "'")}\"\n"
        )
        if (++lines % 10 == 0) w.flush()
    }

    fun flush() = runCatching { writer?.flush() }

    fun close() {
        runCatching { writer?.close() }
        writer = null
    }

    private fun deleteOld(now: Long) {
        val cutoff = now - 7L * 24 * 3600 * 1000
        dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    }
}
