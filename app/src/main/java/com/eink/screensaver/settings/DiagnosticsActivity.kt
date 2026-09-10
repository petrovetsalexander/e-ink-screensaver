package com.eink.screensaver.settings

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.eink.screensaver.EventLog
import com.eink.screensaver.PrefsManager
import com.eink.screensaver.R

/**
 * Everything to do with chasing a bug, off the main settings screen: the event
 * log switch, the share and clear buttons, and the state snapshot.
 *
 * It lives behind its own button at the very bottom of the settings screen
 * because it is not part of setting the app up — most of the time it should be
 * out of the way entirely, and when it is needed it is worth a whole screen.
 */
class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var checkEventLog: CheckBox
    private lateinit var eventLogStatus: TextView
    private lateinit var stateText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostics)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        checkEventLog = findViewById(R.id.checkEventLog)
        eventLogStatus = findViewById(R.id.eventLogStatus)
        stateText = findViewById(R.id.stateText)

        checkEventLog.isChecked = PrefsManager.isEventLogEnabled(this)
        checkEventLog.setOnCheckedChangeListener { _, checked ->
            EventLog.setEnabled(this, checked)
            updateStatus()
        }

        findViewById<Button>(R.id.btnShareLog).setOnClickListener { shareEventLog() }
        findViewById<Button>(R.id.btnClearLog).setOnClickListener {
            EventLog.clear()
            Toast.makeText(this, R.string.event_log_cleared, Toast.LENGTH_SHORT).show()
            // The clear runs on the log's own writer thread; give it a moment
            // before reading the size back, or the label shows the old one.
            eventLogStatus.postDelayed({ updateStatus() }, 300)
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val kb = (EventLog.sizeBytes() / 1024L).toInt()
        eventLogStatus.text = when {
            !PrefsManager.isEventLogEnabled(this) && kb == 0 -> getString(R.string.event_log_off)
            kb == 0 -> getString(R.string.event_log_empty)
            else -> getString(R.string.event_log_size, kb)
        }
        // The same snapshot that heads a shared log. On screen it answers the
        // question the log was usually being shared to answer — whether the
        // accessibility service is actually bound and the vendor wake is
        // available — without anyone having to send a file first.
        stateText.text = EventLog.snapshot(this).trimEnd()
    }

    /**
     * Hands the trace to whatever the user picks — a messenger, mail, a file
     * manager. Plain text through a FileProvider uri rather than `EXTRA_TEXT`:
     * the log outgrows an intent extra within a day, and a file survives being
     * forwarded.
     */
    private fun shareEventLog() {
        val file = try {
            EventLog.export(this)
        } catch (e: Throwable) {
            null
        }
        if (file == null) {
            Toast.makeText(this, R.string.event_log_nothing, Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.event_log_share_title))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, getString(R.string.event_log_share)))
    }
}
