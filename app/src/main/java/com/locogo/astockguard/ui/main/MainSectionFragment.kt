package com.locogo.astockguard.ui.main

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.locogo.astockguard.MainActivity
import com.locogo.astockguard.R
import com.locogo.astockguard.databinding.FragmentMainSectionBinding
import com.locogo.astockguard.integration.ths.ThsTradeSyncActivity
import com.locogo.astockguard.ui.dashboard.DashboardFragment
import com.locogo.astockguard.ui.messages.MessageCenterActivity
import com.locogo.astockguard.ui.stock.StockDetailFragment
import com.locogo.astockguard.designsystem.R as DesignR
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** 按 img 原图组织主页面，共用红色搜索头部，内容采用各自独立 XML 布局。 */
class MainSectionFragment : Fragment() {
    private var viewBinding: FragmentMainSectionBinding? = null
    private val binding get() = requireNotNull(viewBinding)
    private val host get() = requireActivity() as MainActivity
    private var page = ""
    private var amountsHidden = false
    private var query = ""
    private var scrollPosition = 0
    private var renderer: ReferencePageRenderer? = null
    private var openingDetail = false

    /** 恢复页面栏目、隐私和搜索条件，不从界面直接发起网络调用。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page = savedInstanceState?.getString("page") ?: requireArguments().getString("page") ?: "home"
        amountsHidden = savedInstanceState?.getBoolean("amounts_hidden") ?: false
        query = savedInstanceState?.getString("query").orEmpty()
        scrollPosition = savedInstanceState?.getInt("scroll") ?: 0
    }

    /** 创建参考图的共用搜索头部、栏目与滚动容器。 */
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        viewBinding = FragmentMainSectionBinding.inflate(inflater, container, false)
        return binding.root
    }

    /** 页面可见时组合共享行情和指数图表状态，避免多个页面重复启动业务任务。 */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        openingDetail = false
        binding.headerMessages.setOnClickListener { dispatch("messages") }
        binding.sectionSearch.setText(query)
        binding.sectionSearch.doAfterTextChanged {
            query = it?.toString().orEmpty()
            if (page == "watchlist") render()
        }
        binding.sectionSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                host.showReferencePage("watchlist", query)
                true
            } else false
        }
        inflatePage()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(host.dashboardViewModel.uiState, host.referenceCharts.state) { state, charts -> state to charts }
                    .collect { (state, charts) ->
                        renderer?.render(state, charts, amountsHidden, query)
                        renderStatus(state)
                        if (page in listOf("home", "market", "account")) host.referenceCharts.refresh()
                    }
            }
        }
    }

    /** 外部导航选择栏目；同一栏目不重置滚动，搜索只影响当前监控列表。 */
    fun showPage(selected: String, search: String? = null) {
        if (search != null) {
            query = search
            viewBinding?.sectionSearch?.setText(search)
        }
        if (page == selected) return
        page = selected
        scrollPosition = 0
        if (viewBinding != null) inflatePage()
    }

    /** 再次点击底部导航时返回所属主栏目；已在主栏目则保留滚动和搜索条件。 */
    fun showRootPage() {
        showPage(requireArguments().getString("page") ?: "home")
    }

    /** 使用原图对应的 XML 页面替换内容区，维持原生控件及业务入口。 */
    private fun inflatePage() {
        val layout = when (page) {
            "account" -> R.layout.view_reference_account
            "hotspots" -> R.layout.view_reference_hotspots
            "sentiment" -> R.layout.view_reference_sentiment
            "strategy" -> R.layout.view_reference_strategy
            "market" -> R.layout.view_reference_market
            "watchlist" -> R.layout.view_reference_watchlist
            "profile" -> R.layout.view_reference_profile
            "news" -> R.layout.view_reference_news
            else -> R.layout.view_reference_home
        }
        binding.sectionContent.removeAllViews()
        val content = layoutInflater.inflate(layout, binding.sectionContent, true)
        renderer = ReferencePageRenderer(content, ::dispatch)
        renderTabs()
        render()
        binding.sectionScroll.post { viewBinding?.sectionScroll?.scrollTo(0, scrollPosition) }
        if (page in listOf("home", "market", "account")) host.referenceCharts.refresh()
    }

    /** 顶部栏目采用原图下划线选中态，热点和情绪使用浅色二级分段栏。 */
    private fun renderTabs() {
        val redHeader = page in listOf("home", "account")
        val selected = when (page) { "sentiment" -> "market"; else -> page }
        binding.topTabs.setBackgroundColor(ContextCompat.getColor(requireContext(), if (redHeader) DesignR.color.gh_header_end else DesignR.color.gh_card))
        binding.topTabs.removeAllViews()
        listOf("首页" to "home", "自选" to "watchlist", "行情" to "market", "热点" to "hotspots", "策略" to "strategy", "账户" to "account").forEach { (title, route) ->
            addTab(binding.topTabs, title, route, selected == route, redHeader)
        }
        binding.subTabs.removeAllViews()
        binding.subTabs.visibility = if (page in listOf("market", "hotspots", "sentiment")) View.VISIBLE else View.GONE
        if (binding.subTabs.visibility == View.VISIBLE) {
            listOf("大盘行情" to "market", "热点排行" to "hotspots", "市场情绪" to "sentiment").forEach { (title, route) ->
                addTab(binding.subTabs, title, route, page == route, false)
            }
        }
    }

    /** 添加具有文字和下划线双重选中提示的栏目，不依赖颜色作为唯一提示。 */
    private fun addTab(container: LinearLayout, title: String, route: String, selected: Boolean, onRed: Boolean) {
        val column = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            isSelected = selected
            contentDescription = "$title，${if (selected) "已选择" else "切换栏目"}"
            setOnClickListener { dispatch(route) }
        }
        val tint = if (onRed) android.graphics.Color.WHITE else ContextCompat.getColor(requireContext(), if (selected) DesignR.color.gh_red else DesignR.color.gh_secondary)
        column.addView(TextView(requireContext()).apply {
            text = title
            textSize = if (container === binding.subTabs) 12f else 14f
            setTextColor(tint)
            alpha = if (onRed && !selected) 0.8f else 1f
            if (selected) setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            minHeight = dp(35)
        })
        column.addView(View(requireContext()).apply {
            setBackgroundColor(if (selected) tint else android.graphics.Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(2))
        })
        container.addView(column)
    }

    /** 分发导航与业务操作，原驾驶舱以交易工具页面保留。 */
    private fun dispatch(route: String) {
        when {
            route.startsWith("stock:") -> openDetail(StockDetailFragment.newInstance(route.removePrefix("stock:")), route)
            route.startsWith("info:") -> AlertDialog.Builder(requireContext()).setMessage(route.removePrefix("info:")).setPositiveButton("知道了", null).show()
            route == "privacy" -> { amountsHidden = !amountsHidden; render() }
            route == "sync" -> startActivity(Intent(requireContext(), ThsTradeSyncActivity::class.java))
            route == "messages" -> startActivity(Intent(requireContext(), MessageCenterActivity::class.java))
            route == "tools" -> openDetail(DashboardFragment(), "tools")
            route == "settings" -> host.openSettings()
            route == "start_monitor" -> host.startMonitor()
            route == "stop_monitor" -> host.stopMonitor()
            route == "refresh" -> { host.dashboardViewModel.refresh(); host.referenceCharts.refresh(true) }
            route == "sector_refresh" -> host.dashboardViewModel.refreshSectorFlow("INDUSTRY")
            route == "refresh_news" -> host.dashboardViewModel.refreshNews(true)
            route == "ai" -> host.dashboardViewModel.analyze("")
            else -> host.showReferencePage(route)
        }
    }

    /** 详情和交易工具加入返回栈，防止连续点击叠加同一页面。 */
    private fun openDetail(fragment: Fragment, tag: String) {
        if (openingDetail || parentFragmentManager.isStateSaved) return
        openingDetail = true
        parentFragmentManager.beginTransaction().replace(R.id.mainContainer, fragment).addToBackStack(tag).commit()
    }

    /** 立即渲染当前状态，用于隐私切换及无需网络的搜索。 */
    private fun render() {
        renderer?.render(host.dashboardViewModel.uiState.value, host.referenceCharts.state.value, amountsHidden, query)
        renderStatus(host.dashboardViewModel.uiState.value)
    }

    /** 来源与失败提示放在内容末尾，保持参考图首屏层级且不隐藏缓存事实。 */
    private fun renderStatus(state: MainUiState) {
        binding.sectionStatus.text = when {
            state.loading -> "正在更新行情…"
            state.error != null -> "更新提示：${state.error}"
            state.snapshot == null -> "暂无行情 · 可在行情页刷新"
            state.snapshot.dataHealth.isStale -> "缓存行情 · 仅供观察 · ${state.snapshot.dataHealth.source}"
            else -> "行情来源：${state.snapshot.dataHealth.source}"
        }
    }

    /** 将参考布局尺寸转换为屏幕像素。 */
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    /** 保存当前栏目、金额隐私、搜索词及滚动位置，供旋转和系统重建恢复。 */
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("page", page)
        outState.putBoolean("amounts_hidden", amountsHidden)
        outState.putString("query", query)
        outState.putInt("scroll", viewBinding?.sectionScroll?.scrollY ?: scrollPosition)
        super.onSaveInstanceState(outState)
    }

    /** 保存滚动位置，释放旧布局和渲染器引用。 */
    override fun onDestroyView() {
        scrollPosition = binding.sectionScroll.scrollY
        renderer = null
        viewBinding = null
        super.onDestroyView()
    }

    companion object {
        /** 将主导航转换为初始栏目，使系统能通过参数独立重建页面。 */
        fun newInstance(destination: Int) = MainSectionFragment().apply {
            arguments = Bundle().apply {
                putString("page", when (destination) {
                    R.id.nav_market -> "market"
                    R.id.nav_watchlist -> "watchlist"
                    R.id.nav_account -> "account"
                    R.id.nav_profile -> "profile"
                    else -> "home"
                })
            }
        }
    }
}
