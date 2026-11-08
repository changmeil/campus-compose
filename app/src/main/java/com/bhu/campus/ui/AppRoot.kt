package com.bhu.campus.ui

import android.Manifest
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Tasks
import top.yukonga.miuix.kmp.icon.extended.Weeks
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.bhu.campus.ChaoxingLoginActivity
import com.bhu.campus.data.CampusStore
import com.bhu.campus.data.ChaoxingClient
import com.bhu.campus.data.Course
import com.bhu.campus.data.TaskItem
import com.bhu.campus.data.WeatherClient
import com.bhu.campus.ui.screens.EnergyScreen
import com.bhu.campus.ui.screens.ScheduleScreen
import com.bhu.campus.ui.screens.TasksScreen
import com.bhu.campus.ui.screens.TodayScreen
import java.time.LocalDate

private val PAGE_TITLES = listOf("今日", "课程表", "作业与考试", "余额")

/**
 * 启动时自动刷新的阈值：学习通、电费超过这么久没更新就在后台刷一次。
 * 2 小时是用户定的——一天里开几次 App 都能看到比较新的数据，又不会每次开都打接口。
 */
private const val STALE_MS = 2 * 3600_000L

@Composable
fun CampusApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val handler = remember { Handler(Looper.getMainLooper()) }

    val showEnergy = CampusStore.settings.showEnergy
    val pageCount = if (showEnergy) 4 else 3

    /**
     * 页面状态直接以 Pager 的 currentPage 为准，不再额外维护一份页码副本。
     *
     * 之前是「page 变量 + settledPage 回写」的双向同步，有两个毛病：
     * 1) 点 tab 改 page 触发滚动的同时，settledPage 的旧值可能把 page 冲回去，
     *    滚动就被取消，表现就是"点了有时切不回去"；
     * 2) settledPage 只在完全停稳后才变，标题和底栏要等动画结束才跟着走，看着不跟手。
     *
     * 现在 currentPage 是随滑动连续变化的状态，标题、底栏选中态都直接读它，天然跟手；
     * 点 tab / 程序跳转只负责发起一次滚动，没有第二份状态可以打架。
     */
    val pagerState = rememberPagerState { pageCount }
    val page = pagerState.currentPage

    // 关掉电费后页数变少，如果正停在最后一页就先退回来，避免越界
    LaunchedEffect(pageCount) {
        if (pagerState.currentPage > pageCount - 1) pagerState.scrollToPage(pageCount - 1)
    }

    var pageJob by remember { mutableStateOf<Job?>(null) }
    fun goToPage(target: Int) {
        if (target !in 0 until pageCount) return
        pageJob?.cancel()
        pageJob = scope.launch { pagerState.animateScrollToPage(target) }
    }

    var showCourseDialog by remember { mutableStateOf(false) }
    var editingCourse by remember { mutableStateOf<Course?>(null) }
    var draftWeekday by remember { mutableIntStateOf(LocalDate.now().dayOfWeek.value) }
    var draftStart by remember { mutableIntStateOf(1) }

    var showTaskDialog by remember { mutableStateOf(false) }
    var editingTask by remember { mutableStateOf<TaskItem?>(null) }
    var showSettings by remember { mutableStateOf(false) }

    var syncing by remember { mutableStateOf(false) }
    var syncText by remember { mutableStateOf("") }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * 刷新天气。[force] 为 true 时无视 2 小时缓存——刚拿到定位权限时必须强刷，
     * 否则界面还停在用兜底坐标（学校）算出来的那份缓存上。
     */
    fun refreshWeather(force: Boolean = false) {
        scope.launch {
            runCatching {
                if (force) WeatherClient.refresh(context) else WeatherClient.refreshIfStale(context)
            }.onSuccess { CampusStore.updateWeather(WeatherClient.current) }
        }
    }

    // 没定位权限时先弹一次；给了就按当前位置刷天气
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) refreshWeather(force = true)
    }

    // 天气：先读本地缓存立刻显示，再按当前位置刷新（缓存不到 2 小时就不打网络）
    LaunchedEffect(Unit) {
        WeatherClient.load(context)
        CampusStore.updateWeather(WeatherClient.current)
        if (WeatherClient.hasLocationPermission(context)) {
            refreshWeather()
        } else {
            locationLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    fun startSync() {
        if (syncing) return
        syncing = true
        syncText = "准备同步…"
        scope.launch {
            runCatching {
                ChaoxingClient.sync(context) { label, _ ->
                    handler.post { syncText = label }
                }
            }.onSuccess { report ->
                val summary = "课程 ${report.courses} 门 · 作业 ${report.tasks} 条 · 考试 ${report.exams} 场"
                toast(
                    if (report.warnings.isEmpty()) "同步完成：$summary"
                    else "同步完成：$summary（${report.warnings.first()}）"
                )
            }.onFailure { error ->
                val message = error.message ?: "未知错误"
                toast("同步失败：$message")
                if (message.contains("登录")) {
                    CampusStore.updateXxt { it.copy(loggedIn = false) }
                }
            }
            syncing = false
            syncText = ""
        }
    }

    // 学习通：超过 2 小时没同步过就自动同步一次（没登录、或从没同步过就跳过）
    LaunchedEffect(Unit) {
        val xxt = CampusStore.xxt
        if (xxt.loggedIn && xxt.lastSync > 0 && System.currentTimeMillis() - xxt.lastSync >= STALE_MS) {
            startSync()
        }
    }

    val loginLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            ChaoxingClient.collectFromWebView(context)
            CampusStore.updateXxt { it.copy(loggedIn = true) }
            toast("登录成功，开始同步")
            startSync()
        } else {
            toast("没有完成登录")
        }
    }

    BackHandler(enabled = page != 0) { goToPage(0) }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = PAGE_TITLES[page],
                actions = {
                    when (page) {
                        0 -> IconButton(onClick = { showSettings = true }) {
                            Icon(
                                imageVector = MiuixIcons.Settings,
                                contentDescription = "设置",
                                modifier = Modifier.size(20.dp),
                            )
                        }

                        1 -> IconButton(onClick = {
                            editingCourse = null
                            draftWeekday = LocalDate.now().dayOfWeek.value
                            draftStart = 1
                            showCourseDialog = true
                        }) {
                            Icon(
                                imageVector = MiuixIcons.Add,
                                contentDescription = "添加课程",
                                modifier = Modifier.size(22.dp),
                            )
                        }

                        2 -> IconButton(onClick = {
                            editingTask = null
                            showTaskDialog = true
                        }) {
                            Icon(
                                imageVector = MiuixIcons.Add,
                                contentDescription = "添加任务",
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            val pendingCount = CampusStore.tasks.count { !it.done }
            NavigationBar {
                NavigationBarItem(
                    selected = page == 0,
                    onClick = { goToPage(0) },
                    icon = MiuixIcons.Home,
                    label = "今日",
                )
                NavigationBarItem(
                    selected = page == 1,
                    onClick = { goToPage(1) },
                    icon = MiuixIcons.Weeks,
                    label = "课程表",
                )
                NavigationBarItem(
                    selected = page == 2,
                    onClick = { goToPage(2) },
                    icon = MiuixIcons.Tasks,
                    label = "作业",
                    badge = if (pendingCount > 0) {
                        {
                            Box(
                                Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFF0433F))
                            )
                        }
                    } else null,
                )
                if (showEnergy) {
                    NavigationBarItem(
                        selected = page == 3,
                        onClick = { goToPage(3) },
                        icon = BoltIcon,
                        label = "余额",
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { index ->
                when (index) {
                    0 -> TodayScreen(
                        contentPadding = innerPadding,
                        onOpenSchedule = { goToPage(1) },
                        onOpenTasks = { goToPage(2) },
                        onSyncXxt = { startSync() },
                        onLoginXxt = {
                            loginLauncher.launch(
                                android.content.Intent(context, ChaoxingLoginActivity::class.java)
                            )
                        },
                        syncing = syncing,
                    )

                    1 -> ScheduleScreen(
                        contentPadding = innerPadding,
                        onAddCourse = { weekday, start ->
                            editingCourse = null
                            draftWeekday = weekday
                            draftStart = start
                            showCourseDialog = true
                        },
                        onEditCourse = { course ->
                            editingCourse = course
                            showCourseDialog = true
                        },
                    )

                    2 -> TasksScreen(
                        contentPadding = innerPadding,
                        onEditTask = { task ->
                            editingTask = task
                            showTaskDialog = true
                        },
                    )

                    else -> EnergyScreen(
                        contentPadding = innerPadding,
                        onMessage = { toast(it) },
                    )
                }
            }

            if (syncing) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = innerPadding.calculateBottomPadding() + 16.dp)
                        .padding(horizontal = 24.dp),
                ) {
                    Card(insideMargin = CardPadding) {
                        Column {
                            Text(
                                text = "同步学习通",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = syncText,
                                fontSize = 11.sp,
                                color = MiuixTheme.colorScheme.onBackgroundVariant,
                            )
                        }
                    }
                }
            }

        if (showCourseDialog) {
            CourseDialog(
                course = editingCourse,
                defaultWeekday = draftWeekday,
                defaultStart = draftStart,
                onDismiss = { showCourseDialog = false },
                onSave = { course ->
                    CampusStore.upsertCourse(course)
                    showCourseDialog = false
                    toast("已保存")
                },
                onDelete = { course ->
                    CampusStore.deleteCourse(course.id)
                    showCourseDialog = false
                    toast("已删除")
                },
            )
        }

        if (showTaskDialog) {
            TaskDialog(
                task = editingTask,
                onDismiss = { showTaskDialog = false },
                onSave = { task ->
                    CampusStore.upsertTask(task)
                    showTaskDialog = false
                    toast("已保存")
                },
                onDelete = { task ->
                    CampusStore.deleteTask(task.id)
                    showTaskDialog = false
                    toast("已删除")
                },
            )
        }

        if (showSettings) {
            SettingsDialog(
                settings = CampusStore.settings,
                onDismiss = { showSettings = false },
                onSave = { next ->
                    CampusStore.updateSettings { next }
                    showSettings = false
                    toast("设置已保存")
                },
                onClearData = {
                    CampusStore.clearAll()
                    ChaoxingClient.clearCookies(context)
                    showSettings = false
                    toast("数据已清空")
                },
            )
        }
        }
    }
}
