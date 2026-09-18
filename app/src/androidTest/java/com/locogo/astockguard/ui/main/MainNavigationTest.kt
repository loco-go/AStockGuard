package com.locogo.astockguard.ui.main

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.locogo.astockguard.MainActivity
import com.locogo.astockguard.R
import com.locogo.astockguard.ui.stock.StockDetailFragment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 验证真实 Activity 容器中的导航、隐私与重建行为，不修改用户持仓配置。 */
@RunWith(AndroidJUnit4::class)
class MainNavigationTest {
    /** 策略子栏目内重选首页应返回首页布局，不创建额外 Fragment。 */
    @Test fun reselectHomeReturnsFromStrategy() {
        launchActivity().use { scenario ->
            scenario.onActivity { activity ->
                activity.showReferencePage("strategy")
                val root = activity.supportFragmentManager.primaryNavigationFragment!!
                assertNotNull(root.requireView().findViewById<View>(R.id.strategyTodayLabel))
                activity.findViewById<BottomNavigationView>(R.id.mainNavigation).selectedItemId = R.id.nav_home
                assertSame(root, activity.supportFragmentManager.primaryNavigationFragment)
                assertNotNull(root.requireView().findViewById<View>(R.id.homeGauge))
                assertNull(root.requireView().findViewById<View>(R.id.strategyTodayLabel))
            }
        }
    }

    /** 五项页面可切换；重复选择不累积 Fragment，搜索在切页与重建后保留。 */
    @Test fun navigationRetainsSearchWithoutDuplicatingRoots() {
        launchActivity().use { scenario ->
            scenario.onActivity { activity ->
                val navigation = activity.findViewById<BottomNavigationView>(R.id.mainNavigation)
                listOf(R.id.nav_market, R.id.nav_watchlist, R.id.nav_account, R.id.nav_profile, R.id.nav_home).forEach {
                    navigation.selectedItemId = it
                    assertTrue(activity.supportFragmentManager.findFragmentByTag("main:$it")!!.isVisible)
                }
                navigation.selectedItemId = R.id.nav_watchlist
                activity.findViewById<TextInputEditText>(R.id.sectionSearch).setText("000001")
                navigation.selectedItemId = R.id.nav_account
                navigation.selectedItemId = R.id.nav_watchlist
                navigation.selectedItemId = R.id.nav_watchlist
                assertEquals("000001", activity.findViewById<TextInputEditText>(R.id.sectionSearch).text.toString())
                assertEquals(5, activity.supportFragmentManager.fragments.size)
                assertEquals(0, activity.supportFragmentManager.backStackEntryCount)
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertEquals(R.id.nav_watchlist, activity.findViewById<BottomNavigationView>(R.id.mainNavigation).selectedItemId)
                assertEquals("000001", activity.findViewById<TextInputEditText>(R.id.sectionSearch).text.toString())
            }
        }
    }

    /** 详情及其系统重建期间隐藏导航，返回后恢复原证券列表的搜索条件。 */
    @Test fun detailBackRestoresOriginAfterRecreation() {
        launchActivity().use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<BottomNavigationView>(R.id.mainNavigation).selectedItemId = R.id.nav_watchlist
                activity.findViewById<TextInputEditText>(R.id.sectionSearch).setText("平安")
                activity.supportFragmentManager.beginTransaction()
                    .replace(R.id.mainContainer, StockDetailFragment.newInstance("000001.SZ"))
                    .addToBackStack("stock:000001.SZ").commit()
                activity.supportFragmentManager.executePendingTransactions()
                assertEquals(View.GONE, activity.findViewById<View>(R.id.mainNavigation).visibility)
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertEquals(View.GONE, activity.findViewById<View>(R.id.mainNavigation).visibility)
                assertTrue(activity.supportFragmentManager.popBackStackImmediate())
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.mainNavigation).visibility)
                assertEquals("平安", activity.findViewById<TextInputEditText>(R.id.sectionSearch).text.toString())
                activity.onBackPressedDispatcher.onBackPressed()
                assertEquals(R.id.nav_home, activity.findViewById<BottomNavigationView>(R.id.mainNavigation).selectedItemId)
            }
        }
    }

    /** 隐藏金额后旋转重建，资产及持仓卡继续隐藏，显式恢复后重新显示。 */
    @Test fun accountPrivacySurvivesRecreation() {
        launchActivity().use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<BottomNavigationView>(R.id.mainNavigation).selectedItemId = R.id.nav_account
                activity.findViewById<View>(R.id.privacyToggle).performClick()
                assertTrue(descendants(activity.findViewById(R.id.sectionContent)).filterIsInstance<TextView>().any { it.text.contains("••••") })
            }
            scenario.recreate()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val button = activity.findViewById<View>(R.id.privacyToggle)
                assertEquals("显示金额", button.contentDescription)
                assertTrue(descendants(activity.findViewById(R.id.sectionContent)).filterIsInstance<TextView>().any { it.text.contains("••••") })
                button.performClick()
                assertFalse(descendants(activity.findViewById(R.id.sectionContent)).filterIsInstance<TextView>().any { it.text.contains("••••") })
            }
        }
    }

    /** 遍历真实视图树查找可见文本及按钮，避免测试依赖绝对屏幕坐标。 */
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) {
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
    } else emptyList()

    /** 沿用项目既有设备测试的直接启动方式，避开厂商系统中的 ActivityScenario 启动等待。 */
    private fun launchActivity(): ActivitySession {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        instrumentation.waitForIdleSync()
        return ActivitySession(activity)
    }

    /** 仅持有当前测试 Activity，不写入应用持仓或账户配置。 */
    private class ActivitySession(private var activity: MainActivity) : AutoCloseable {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()

        /** 在主线程执行视图操作，确保断言与 Fragment 事务采用真实线程约束。 */
        fun onActivity(block: (MainActivity) -> Unit) = instrumentation.runOnMainSync { block(activity) }

        /** 监听重建后的 Activity；十秒内未恢复则明确失败，避免测试无限等待。 */
        fun recreate() {
            val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
            try {
                onActivity { it.recreate() }
                activity = instrumentation.waitForMonitorWithTimeout(monitor, 10_000) as? MainActivity
                    ?: throw AssertionError("主页面重建超时")
                instrumentation.waitForIdleSync()
            } finally {
                instrumentation.removeMonitor(monitor)
            }
        }

        /** 结束测试页面并等待其销毁，防止影响下一项测试。 */
        override fun close() {
            onActivity { it.finish() }
            instrumentation.waitForIdleSync()
        }
    }
}
