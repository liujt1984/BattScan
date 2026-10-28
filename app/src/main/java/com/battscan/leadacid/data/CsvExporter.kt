package com.battscan.leadacid.data

import android.content.Context
import com.battscan.leadacid.data.db.SampleDao
import com.battscan.leadacid.data.db.SessionDao
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Session export for offline analysis - the same column shape as the HCI decode dump, so a captured
 * ride and a bench log can be opened side by side in the same spreadsheet.
 */
class CsvExporter(private val context: Context, private val sessionDao: SessionDao, private val sampleDao: SampleDao) {

    private val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA)

    suspend fun export(sessionId: Long): File {
        val session = requireNotNull(sessionDao.byId(sessionId)) { "会话 $sessionId 不存在" }
        val samples = sampleDao.forSession(sessionId)
        val out = File(exportDir(), "battery_session_${stamp.format(Date(session.startedAtMs))}.csv")
        out.bufferedWriter().use { writer ->
            writer.append(HEADER)
            writer.newLine()
            samples.forEach { s ->
                writer.append(
                    listOf(
                        s.elapsedMs.toString(),
                        "%.3f".format(s.voltageV),
                        "%.3f".format(s.currentA),
                        "%.2f".format(s.powerW),
                        s.direction,
                        s.tempC?.let { "%.1f".format(it) } ?: "",
                        "%.2f".format(s.socPercent),
                        s.rawCurrentA.toString(),
                        "\"${s.frameHex}\"",
                    ).joinToString(",")
                )
                writer.newLine()
            }
        }
        return out
    }

    private fun exportDir(): File =
        context.getExternalFilesDir("exports")?.also { it.mkdirs() } ?: context.filesDir

    companion object {
        const val HEADER = "elapsed_ms,voltage_v,current_a,power_w,direction,temp_c,soc_percent,raw_current_a,frame_hex"
    }
}
