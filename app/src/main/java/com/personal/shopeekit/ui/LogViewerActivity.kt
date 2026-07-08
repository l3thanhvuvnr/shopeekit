package com.personal.shopeekit.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.personal.shopeekit.R
import com.personal.shopeekit.core.logging.KitLogger
import com.personal.shopeekit.core.logging.KitLogger.Entry
import com.personal.shopeekit.core.logging.KitLogger.Level
import com.personal.shopeekit.databinding.ActivityLogViewerBinding
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogViewerBinding
    private lateinit var recycler: RecyclerView
    private lateinit var tvCount: TextView
    private lateinit var adapter: LogAdapter

    private var filterTags: Set<String>? = null

    private val CHECKOUT_TAGS = setOf("ENG", "ACC", "UI", "ORP")
    private val PRICE_TAGS    = setOf("PRC", "SCH", "ALT")
    private val NETWORK_TAGS  = setOf("NET", "TSY", "RTT", "HTTP")

    @OptIn(FlowPreview::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        recycler = binding.recyclerLog
        tvCount  = binding.tvEntryCount

        adapter = LogAdapter()
        recycler.layoutManager = LinearLayoutManager(this).also { it.stackFromEnd = true }
        recycler.adapter = adapter

        // Filter chips
        binding.chipGroupFilter.setOnCheckedChangeListener { _, checkedId ->
            filterTags = when (checkedId) {
                R.id.chipCheckout -> CHECKOUT_TAGS
                R.id.chipPrice    -> PRICE_TAGS
                R.id.chipNetwork  -> NETWORK_TAGS
                else              -> null
            }
            refreshList()
        }

        // Buttons
        binding.btnShare.setOnClickListener { shareLog() }
        binding.btnClear.setOnClickListener {
            KitLogger.clear()
            refreshList()
        }

        // Load existing entries immediately
        refreshList()

        // Coalesce bursts: refreshList() rebuilds from the full ring buffer, so a
        // per-entry refresh would thrash during a snipe. sample() caps it to ~7/s,
        // and repeatOnLifecycle stops the collection while the screen isn't visible.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                KitLogger.flow.sample(150).collect { refreshList() }
            }
        }
    }

    private fun refreshList() {
        val all = KitLogger.getEntries()
        val filtered = if (filterTags == null) all
                       else all.filter { it.tag in filterTags!! }
        adapter.submitList(filtered.toList())
        tvCount.text = "${filtered.size} entries"
        if (filtered.isNotEmpty()) recycler.scrollToPosition(filtered.size - 1)
    }

    private fun shareLog() {
        val uri = KitLogger.exportToFile(this) ?: run {
            Toast.makeText(this, "Không tạo được file log", Toast.LENGTH_SHORT).show()
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

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val tv = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_log_entry, parent, false) as TextView
            return VH(tv)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val e = getItem(position)
            holder.tv.text = "${e.level.label}/${TS_FMT.format(Date(e.ts))} [${e.tag}] ${e.msg}"
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
                override fun areItemsTheSame(a: Entry, b: Entry) =
                    a.ts == b.ts && a.tag == b.tag && a.msg == b.msg
                override fun areContentsTheSame(a: Entry, b: Entry) = a == b
            }
        }
    }
}
