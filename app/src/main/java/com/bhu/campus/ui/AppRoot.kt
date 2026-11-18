package com.bhu.campus.ui

import android.Manifest
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
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
import kotlinx.coroutines.delay
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
import com.bhu.campus.data.IslandAlerts
import com.bhu.campus.data.IslandHub
import com.bhu.campus.data.Ujing
import com.bhu.campus.data.EnergySync
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
    var showImport by remember { mutableStateOf(false) }
    var editingCourse by remember { mutableStateOf<Course?>(null) }
    var draftWeekday by remember { mutableIntStateOf(LocalDate.now().dayOfWeek.value) }
    var draftStart by remember { mutableIntStateOf(1) }

    var showTaskDialog by remember { mutableStateOf(false) }
    var editingTask by remember { mutableStateOf<TaskItem?>(null) }
    var showSettings by remember { mutableStateOf(false) }

    var syncing by remember { mutableStateOf(false) }
    var syncText by remember { mutableStateOf("") }
    var energyRefreshing by remember { mutableStateOf(false) }

    var showUjingLogin by remember { mutableStateOf(false) }
    var showUjingAdd by remember { mutableStateOf(false) }
    var showUjingManage by remember { mutableStateOf(false) }
    var showUjingWash by remember { mutableStateOf(false) }
    var ujingReserveDevice by remember { mutableStateOf<Ujing.DeviceStatus?>(null) }
    var ujingBusy by remember { mutableStateOf(false) }

    /**
     * 查一次当前订单，决定卡片和岛上显示到哪一步了。
     *
     * 以前只查 `orders/running`，而它只回「运行中」那批 —— 付完钱还没启动的单
     * 根本不在这里面，于是岛上一直停在「待支付」，谁也看不到机器到底转没转、
     * 还有多久结束。现在改成以 `orders/{id}/detail` 为准：状态码直接告诉我们是
     * 已支付、自洁中还是洗涤中，剩余时间也由它给。
     *
     * 没有订单号时（别人下单、或老版本没存）再退回 running 去发现订单。
     * 只在真有订单时动岛——没有订单就不碰，免得把洗澡的预约码岛清掉。
     */
    suspend fun applyOrderIsland(session: Ujing.Session) {
        var id = CampusStore.ujing.orderId
        if (id.isBlank()) {
            // 没有记录（别人下的单、或老版本没存）：去 running 里捞一个补上
            val (running, _) = Ujing.runningOrders(session)
            val first = running.firstOrNull {
                !it.statusText.contains("完成") && !it.statusText.contains("取消")
            } ?: return
            id = first.id
            CampusStore.setUjingOrderId(id)
        }

        val (detail, err) = Ujing.orderDetail(session, id)
        if (detail == null) {
            if (err.isNotBlank()) Log.w("CampusIsland", "detail $id: $err")
            return
        }

        CampusStore.setUjingOrderNow(detail)
        val label = detail.label.ifBlank { "洗衣机" }

        if (detail.ended) {
            // 洗完了就留句话在岛上（10 分钟自己收）；超时/取消/失败则直接撤掉
            if (detail.status == Ujing.OrderStatus.DONE) {
                IslandHub.washDone(context, label)
            } else {
                IslandHub.dismissWash(context)
            }
            CampusStore.setUjingOrderId("")
            return
        }

        when (detail.status) {
            Ujing.OrderStatus.BOOKED -> IslandHub.wash(
                context,
                IslandHub.WashPhase.WAIT_PAY,
                label,
                System.currentTimeMillis() +
                    (detail.remainSeconds.takeIf { it > 0 } ?: 180) * 1000L,
            )

            Ujing.OrderStatus.PAID, Ujing.OrderStatus.PROTECT -> IslandHub.wash(
                context,
                IslandHub.WashPhase.PAID,
                label,
            )

            Ujing.OrderStatus.SELF_CLEAN_START, Ujing.OrderStatus.SELF_CLEANING -> IslandHub.wash(
                context,
                IslandHub.WashPhase.SELF_CLEAN,
                label,
                System.currentTimeMillis() + (detail.remainSeconds.takeIf { it > 0 } ?: 180) * 1000L,
            )

            Ujing.OrderStatus.SELF_CLEAN_DONE -> IslandHub.wash(
                context,
                IslandHub.WashPhase.READY,
                label,
                System.currentTimeMillis() + (detail.remainSeconds.takeIf { it > 0 } ?: 300) * 1000L,
            )

            Ujing.OrderStatus.STARTING, Ujing.OrderStatus.LIQUID,
            Ujing.OrderStatus.RUNNING, Ujing.OrderStatus.FAULT,
            -> {
                val remain = detail.remainSeconds
                IslandHub.wash(
                    context,
                    IslandHub.WashPhase.RUNNING,
                    label,
                    if (remain > 0) System.currentTimeMillis() + remain * 1000L else 0L,
                    rightText = if (remain > 0) "" else "进行中",
                )
            }
        }
    }

    /** 挨个复查已绑定的机器。谁失败了只在全都失败时把原因显示出来。 */
    fun refreshUjing() {
        val ujing = CampusStore.ujing
        if (!ujing.loggedIn || ujing.bindings.isEmpty() || ujingBusy) return
        ujingBusy = true
        scope.launch {
            val session = Ujing.Session(ujing.mobile, ujing.token)
            val out = mutableListOf<Ujing.DeviceStatus>()
            var firstError = ""
            ujing.bindings.forEach { binding ->
                val (status, err) = Ujing.scanWasher(binding.qrCode, session)
                if (status != null) out += status else if (firstError.isBlank()) firstError = err
            }
            CampusStore.setUjingDevices(
                out,
                if (out.isEmpty()) firstError else "",
            )
            ujingBusy = false
            applyOrderIsland(session)
        }
    }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun refreshEnergy(silent: Boolean = false) {
        if (energyRefreshing) return
        if (!EnergySync.isConfigured()) {
            if (!silent) {
                if (showEnergy) {
                    toast("先填好楼号和房间号")
                    goToPage(3)
                } else {
                    toast("设置里开启「显示宿舍电费」后才能查询")
                }
            }
            return
        }
        energyRefreshing = true
        scope.launch {
            runCatching { EnergySync.refresh() }
                .onSuccess {
                    if (!silent) toast("余额 ¥" + String.format("%.2f", it.balance))
                }
                .onFailure {
                    if (!silent) toast("电费查询失败：${it.message ?: "未知错误"}")
                }
            energyRefreshing = false
        }
    }

    // 电费：超过 2 小时没查过就自动查一次（未配置或关掉电费会自动跳过）
    LaunchedEffect(Unit) {
        runCatching { EnergySync.autoIfStale(STALE_MS) }
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

    /**
     * 付完钱自动把洗衣对话框推到面前。
     *
     * 付款是在 U净 小程序 / 微信里完成的，回到 App 时机器其实已经在等启动 ——
     * 这一步不做，用户看到「已支付」也不知道该点哪儿，机器空等到订单超时。
     * 同一张单只自动弹一次，弹过之后由用户自己点卡片上的「去启动」。
     */
    var autoOpenedOrderId by remember { mutableStateOf("") }
    LaunchedEffect(CampusStore.ujingOrder?.orderId, CampusStore.ujingOrder?.status) {
        val order = CampusStore.ujingOrder ?: return@LaunchedEffect
        if (order.status != Ujing.OrderStatus.PAID) return@LaunchedEffect
        if (autoOpenedOrderId == order.orderId) return@LaunchedEffect
        val device = CampusStore.ujingDevices.firstOrNull { it.orderId.isNotBlank() }
            ?: return@LaunchedEffect
        autoOpenedOrderId = order.orderId
        ujingReserveDevice = device
        goToPage(3)
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

    /**
     * @param includeSchedule 是否连课表一起刷。手动点「同步」传 true；
     *   自动同步看设置里的开关，默认只拉作业和考试。
     */
    fun startSync(includeSchedule: Boolean = true) {
        if (syncing) return
        syncing = true
        syncText = "准备同步…"
        scope.launch {
            runCatching {
                ChaoxingClient.sync(context, includeSchedule) { label, _ ->
                    handler.post { syncText = label }
                }
            }.onSuccess { report ->
                val summary = buildList {
                    if (report.scheduleSynced) add("课程 ${report.courses} 门")
                    add("作业 ${report.tasks} 条")
                    add("考试 ${report.exams} 场")
                }.joinToString(" · ")
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

    // 学习通：满足自动同步条件就静默拉一次。
    // 条件见 CampusStore.shouldAutoSync：开关打开 + 距上次 2 小时 + 90 天内有作业/考试。
    LaunchedEffect(Unit) {
        if (CampusStore.shouldAutoSync()) {
            startSync(includeSchedule = CampusStore.settings.autoSyncSchedule)
        }
    }

    // 洗衣房状态：打开就看一眼，省得进 App 再点一次刷新
    LaunchedEffect(Unit) {
        refreshUjing()
    }

    // 洗衣订单轮询：付款是在微信里完成的，App 根本收不到回调，付完人就走开了。
    // 只有定时查一次，才知道机器到底开始洗没有——卡片和岛也就跟着从「待支付」
    // 走到「待启动」→「自洁中」→「洗涤中」。
    // 跟着单子时 15 秒一次（自洁结束后只有 5 分钟启动窗口，慢了就错过），没单子时 60 秒。
    LaunchedEffect(Unit) {
        while (true) {
            delay(if (CampusStore.ujing.orderId.isNotBlank()) 15_000L else 60_000L)
            val ujing = CampusStore.ujing
            if (!ujing.loggedIn) continue
            runCatching { applyOrderIsland(Ujing.Session(ujing.mobile, ujing.token)) }
        }
    }

    // 超级岛：先把倒计时循环拉起来（无障碍服务开着的话进程常驻，循环就不会停），
    // 再看一眼有没有该提醒的作业/余额。
    LaunchedEffect(Unit) {
        IslandHub.attach(context)
        IslandAlerts.check(context)
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
                        onImportWakeup = { showImport = true },
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
                        onImport = { showImport = true },
                        onSync = { startSync() },
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
                        ujingBusy = ujingBusy,
                        onUjingLogin = { showUjingLogin = true },
                        onUjingRefresh = { refreshUjing() },
                        onUjingManage = { showUjingManage = true },
                        onUjingWash = { showUjingWash = true },
                        onUjingReserve = { ujingReserveDevice = it },
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

        if (showImport) {
            WakeupImportDialog(
                onDismiss = { showImport = false },
                onImport = { list, monday, times, replace ->
                    CampusStore.importCourses(list, monday, replace, times)
                    showImport = false
                    val suffix = buildString {
                        append(if (replace) "（覆盖原课表）" else "（追加）")
                        if (times.isNotEmpty()) append("，已同步作息时间")
                    }
                    toast("已导入 ${list.size} 门课$suffix")
                },
            )
        }

        if (showUjingLogin) {
            UjingLoginDialog(
                onDismiss = { showUjingLogin = false },
                onSuccess = {
                    showUjingLogin = false
                    // 刚登录完手里通常还没有机器，直接把「添加」推出来
                    if (CampusStore.ujing.bindings.isEmpty()) showUjingAdd = true else refreshUjing()
                },
            )
        }

        if (showUjingAdd) {
            UjingAddDeviceDialog(
                onDismiss = { showUjingAdd = false },
                onAdded = {
                    showUjingAdd = false
                    refreshUjing()
                },
            )
        }

        if (showUjingManage) {
            UjingManageDialog(
                onDismiss = { showUjingManage = false },
                onAdd = {
                    showUjingManage = false
                    showUjingAdd = true
                },
                onLogout = {
                    CampusStore.logoutUjing()
                    showUjingManage = false
                    toast("已退出 U净")
                },
                onOpenWash = {
                    showUjingManage = false
                    showUjingWash = true
                },
            )
        }

        if (showUjingWash) {
            UjingWashDialog(onDismiss = { showUjingWash = false })
        }

        ujingReserveDevice?.let { device ->
            UjingReserveDialog(
                device = device,
                onDismiss = { ujingReserveDevice = null },
                onReserved = { refreshUjing() },
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
                onResetSectionTimes = { CampusStore.resetSectionTimes() },
            )
        }
        }
    }
}
