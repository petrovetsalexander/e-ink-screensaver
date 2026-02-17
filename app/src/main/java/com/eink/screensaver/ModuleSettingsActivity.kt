package com.eink.screensaver

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Collections

class ModuleSettingsActivity : AppCompatActivity() {

    private lateinit var adapter: ModuleAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper
    private val modules = mutableListOf<ModuleItem>()

    data class ModuleItem(
        val key: String,
        var enabled: Boolean
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_module_settings)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        loadModules()

        val recyclerView = findViewById<RecyclerView>(R.id.modulesRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = ModuleAdapter()
        recyclerView.adapter = adapter

        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.adapterPosition
                val to = target.adapterPosition
                Collections.swap(modules, from, to)
                adapter.notifyItemMoved(from, to)
                saveOrder()
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    viewHolder?.itemView?.setBackgroundColor(0xFFE8E8E8.toInt())
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.setBackgroundColor(Color.WHITE)
            }
        }

        itemTouchHelper = ItemTouchHelper(callback)
        itemTouchHelper.attachToRecyclerView(recyclerView)

        // Navigation buttons
        findViewById<Button>(R.id.btnClockSettings).setOnClickListener {
            startActivity(Intent(this, ClockSettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btnWeatherSettings).setOnClickListener {
            startActivity(Intent(this, WeatherSettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btnNewsSettings).setOnClickListener {
            startActivity(Intent(this, NewsSettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btnNotesSettings).setOnClickListener {
            startActivity(Intent(this, NotesSettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btnBookSettings).setOnClickListener {
            startActivity(Intent(this, BookSettingsActivity::class.java))
        }
    }

    private fun loadModules() {
        val order = PrefsManager.getModulesOrder(this)
        modules.clear()
        for (key in order) {
            modules.add(ModuleItem(key, isModuleEnabled(key)))
        }
    }

    private fun isModuleEnabled(key: String): Boolean = when (key) {
        "clock" -> PrefsManager.isBlockClockEnabled(this)
        "weather" -> PrefsManager.isBlockWeatherEnabled(this)
        "news" -> PrefsManager.isBlockNewsEnabled(this)
        "notes" -> PrefsManager.isBlockNotesEnabled(this)
        "book" -> PrefsManager.isBlockBookEnabled(this)
        else -> false
    }

    private fun setModuleEnabled(key: String, enabled: Boolean) {
        when (key) {
            "clock" -> PrefsManager.setBlockClockEnabled(this, enabled)
            "weather" -> PrefsManager.setBlockWeatherEnabled(this, enabled)
            "news" -> PrefsManager.setBlockNewsEnabled(this, enabled)
            "notes" -> PrefsManager.setBlockNotesEnabled(this, enabled)
            "book" -> PrefsManager.setBlockBookEnabled(this, enabled)
        }
    }

    private fun moduleDisplayName(key: String): String = when (key) {
        "clock" -> getString(R.string.module_clock)
        "weather" -> getString(R.string.module_weather)
        "news" -> getString(R.string.module_news)
        "notes" -> getString(R.string.module_notes)
        "book" -> getString(R.string.module_book)
        else -> key
    }

    private fun saveOrder() {
        PrefsManager.setModulesOrder(this, modules.map { it.key })
    }

    inner class ModuleViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val dragHandle: TextView = view.findViewWithTag("drag")
        val checkBox: CheckBox = view.findViewWithTag("checkbox")
        val nameText: TextView = view.findViewWithTag("name")
    }

    @SuppressLint("ClickableViewAccessibility")
    inner class ModuleAdapter : RecyclerView.Adapter<ModuleViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ModuleViewHolder {
            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                )
                setPadding(0, 12, 0, 12)
                setBackgroundColor(Color.WHITE)
            }

            val dragHandle = TextView(parent.context).apply {
                text = "\u2630"
                textSize = 20f
                setTextColor(0xFF999999.toInt())
                setPadding(8, 0, 16, 0)
                tag = "drag"
            }

            val checkBox = CheckBox(parent.context).apply {
                tag = "checkbox"
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val nameText = TextView(parent.context).apply {
                textSize = 16f
                setTextColor(0xFF000000.toInt())
                tag = "name"
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            }

            row.addView(dragHandle)
            row.addView(checkBox)
            row.addView(nameText)

            return ModuleViewHolder(row)
        }

        override fun onBindViewHolder(holder: ModuleViewHolder, position: Int) {
            val item = modules[position]
            holder.nameText.text = moduleDisplayName(item.key)
            holder.checkBox.setOnCheckedChangeListener(null)
            holder.checkBox.isChecked = item.enabled
            holder.checkBox.setOnCheckedChangeListener { _, isChecked ->
                val pos = holder.adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    modules[pos].enabled = isChecked
                    setModuleEnabled(modules[pos].key, isChecked)
                }
            }

            holder.dragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    itemTouchHelper.startDrag(holder)
                }
                false
            }
        }

        override fun getItemCount() = modules.size
    }
}
