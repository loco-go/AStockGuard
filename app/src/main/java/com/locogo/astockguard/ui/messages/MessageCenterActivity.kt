package com.locogo.astockguard.ui.messages

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.*
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import com.locogo.astockguard.appContainer
import com.locogo.astockguard.applySystemBarInsets
import kotlinx.coroutines.launch

/** 消息入口和通知点击共用页面，只渲染 ViewModel 数据，不调用行情接口。 */
class MessageCenterActivity : AppCompatActivity() {
    private val model by lazy {
        ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = MessageCenterViewModel(appContainer.monitorMessageRepository) as T
        })[MessageCenterViewModel::class.java]
    }
    private lateinit var spinner: Spinner
    private val types = listOf("", "DYNAMIC_T", "VOLUME_RADAR", "SECTOR_FLOW")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 12, 24, 12) }
        root.addView(TextView(this).apply { text = "消息"; textSize = 24f; setPadding(12, 16, 12, 16) })
        root.addView(TextView(this).apply { text = "做T、量能与板块资金历史。点击消息查看完整内容，清除通知不会删除记录。"; setPadding(12, 0, 12, 12) })
        spinner = Spinner(this).apply { adapter = ArrayAdapter(this@MessageCenterActivity, android.R.layout.simple_spinner_dropdown_item, listOf("全部消息", "做T提醒", "量能提醒", "板块资金")) }
        root.addView(spinner)
        val empty = TextView(this).apply { text = "暂无此类消息。启动监控后，满足条件的提醒会保存在这里。"; setPadding(12, 24, 12, 24) }
        root.addView(empty)
        val adapter = MessageAdapter { row -> AlertDialog.Builder(this).setTitle("消息详情").setMessage(row.detail).setPositiveButton("关闭", null).show() }
        val list = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@MessageCenterActivity); this.adapter = adapter }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(Button(this).apply { text = "加载更早消息"; setOnClickListener { model.more() } })
        root.addView(Button(this).apply { text = "返回"; setOnClickListener { finish() } })
        setContentView(root)
        applySystemBarInsets(root)
        if (savedInstanceState == null) model.select(intent.getStringExtra("message_type").orEmpty().takeIf(types::contains).orEmpty())
        spinner.setSelection(types.indexOf(model.filter.value).coerceAtLeast(0))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { if (model.filter.value != types[position]) model.select(types[position]) }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.rows.collect { rows -> adapter.submit(rows); empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val type = intent.getStringExtra("message_type").orEmpty().takeIf(types::contains).orEmpty()
        model.select(type); spinner.setSelection(types.indexOf(type))
    }
}

private class MessageAdapter(private val open: (MessageRow) -> Unit) : RecyclerView.Adapter<MessageAdapter.Holder>() {
    private var rows = emptyList<MessageRow>()
    class Holder(val box: LinearLayout, val title: TextView, val body: TextView) : RecyclerView.ViewHolder(box)
    fun submit(values: List<MessageRow>) { rows = values; notifyDataSetChanged() }
    override fun getItemCount() = rows.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val box = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(16, 24, 16, 24)
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val title = TextView(parent.context).apply { textSize = 17f; setTypeface(typeface, android.graphics.Typeface.BOLD) }
        val body = TextView(parent.context).apply { textSize = 14f; maxLines = 5; ellipsize = android.text.TextUtils.TruncateAt.END }
        box.addView(title); box.addView(body)
        return Holder(box, title, body)
    }
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = rows[position]
        holder.title.text = row.title; holder.body.text = row.summary
        holder.box.setOnClickListener { open(row) }
    }
}
