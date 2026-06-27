package com.personal.shopeekit.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.ChipGroup
import com.personal.shopeekit.R
import com.personal.shopeekit.core.logging.KitLogger
import com.personal.shopeekit.core.logging.KitLogger.Entry
import com.personal.shopeekit.core.logging.KitLogger.Level
import com.personal.shopeekit.databinding.ActivityLogViewerBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogViewerBinding
    private lateinit var adapter: LogAdapter
    private var filterTags: Set<String>? = null  // null = all

    private val CHECKOUT_TAGS = setOf("ENG", "ACC", "UI", "ORP")
    private val PRICE_TAGS = setOf("PRC", "SCH", "ALT")
    private val NETWORK_TAGS = setOf("NET", "TSY", "RTT", "HTTP")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        adapter = LogAdapter()
        binding.recyclerLog.apply {
            layoutManager = LinearLayoutManager(this@LogViewerActivity).also {
                it.stackFromEnd = true
            }
            adapter = this@LogViewerActivity.adapter
        }

        // Load existing buffer immediately
        refreshList()

        // Collect new entries in real-time
        lifecycleScope.launch {
            KitLogger.flow.collectLatest {
                refreshList()
            }
        }

        // Filter chips
        binding.chipGroupFilter.setOnCheckedStateChangeListener { _, ids ->
            filterTags = when (ids.firstOrNull()) {
                R.id.chipCheckout -> CHECKOUT_TAGS
                R.id.chipPrice -> PRICE_TAGS
                R.id.chipNetwork -> NETWORK_TAGS
                else -> null
            }
            refreshList()
        }

        binding.btnShare.setOnClickListener { shareLog() }
        binding.btnClear.setOnClickListener {
            KitLogger.clear()
            refreshList()
        }
    }

    private fun refreshList() {
        val all = KitLogger.getEntries()
        val filtered = if (filterTags == null) all
        else all.filter { it.tag in filterTags!! }
        adapter.submitList(filtered.toList())
        binding.tvEntryCount.text = "${filtered.size} entries"
        // auto-scroll to bottom
        if (filtered.isNotEmpty()) {
            binding.recyclerLog.scrollToPosition(filtered.size - 1)
        }
    }

    private fun shareLog() {
        val uri = KitLogger.exportToFile(this) ?: run {
            android.widget.Toast.makeText(this, "Không tạo được file log", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "ShopeeKit Debug Log — ${Date()}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Chia sẻ log"))
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    // ─── Adapter ────────────────────────────────────────────────────────────

    private class LogAdapter : ListAdapter<Entry, LogAdapter.VH>(DIFF) {

        private val TS_FMT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

        inner class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            val tv = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_log_entry, parent, false) as TextView
            return VH(tv)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val e = getItem(position)
            val time = TS_FMT.format(Date(e.ts))
            holder.tv.text = "${e.level.label}/$time [${e.tag}] ${e.msg}"
            holder.tv.setTextColor(colorFor(e.level))
        }

        private fun colorFor(level: Level) = when (level) {
            Level.DEBUG -> Color.parseColor("#888888")
            Level.INFO  -> Color.parseColor("#DDDDDD")
            Level.WARN  -> Color.parseColor("#FFD700")
            Level.ERROR -> Color.parseColor("#FF4444")
        }

        companion object {
            val DIFF = object : DiffUtil.ItemCallback<Entry>() {
                override fun areItemsTheSame(a: Entry, b: Entry) = a.ts == b.ts && a.tag == b.tag && a.msg == b.msg
                override fun areContentsTheSame(a: Entry, b: Entry) = a == b
            }
        }
    }
}
