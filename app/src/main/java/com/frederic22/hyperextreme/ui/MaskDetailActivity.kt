package com.frederic22.hyperextreme.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.frederic22.hyperextreme.applyAppSystemBars
import com.frederic22.hyperextreme.data.MaskPrefs
import com.frederic22.hyperextreme.data.MaskRole
import com.frederic22.hyperextreme.data.MaskStore
import com.frederic22.hyperextreme.requestHighRefreshRate
import com.frederic22.hyperextreme.resolveAppDark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.window.WindowDialog
import java.io.File

/**
 * 二级页（「锁屏遮罩替换」 / 「系统界面」）的宿主 —— **一个真的 Activity**。
 *
 * ## 为什么必须是 Activity（2026-09-29 用户点破）
 *
 * 二级页的转场要的是**系统转场**（参考应用 HyperCeiler 的观感）。HyperCeiler 的主题
 * `Base.Theme.HyperCeiler parent="Theme.AppCompat.DayNight"` **没有自定义 `windowAnimationStyle`**，
 * 二级页又是**新 Activity** ⇒ 它的转场完全由系统给（`Theme` → `Animation.Activity` → 那四个 anim）。
 *
 * 我们前面两版都在**同一个 Activity 里用 Compose 自己复刻**：先照 HyperChanger 抄，再照 AOSP 的
 * 四个 anim 抄 —— 两次都被否掉（"动画还是错的"）。原因就是**复刻 ≠ 系统**：抄的是 AOSP 的默认值，
 * 而真机上跑的是**这台 ROM 改过的**那份，抄多准都可能不一样。改成真 Activity 之后，转场由系统负责，
 * **和系统里其它应用（含 HyperCeiler）走的是同一条代码路径，不存在"抄错"这回事**。
 *
 * 本应用主题 `@style/Theme.MaskSwap` 继承 `android:Theme.Material.Light.NoActionBar` → … → 基 `Theme`，
 * 链上**没有任何 `windowAnimationStyle`** ⇒ 继承 `@android:style/Animation.Activity`，即系统默认那套。
 *
 * ## 顺带的好处
 *
 * 二级页是独立窗口，**天然盖住底栏、天然有自己的返回栈**（系统返回键 = `finish()`，
 * 不用再 `BackHandler` 拦截）；一级页面（含 pager 位置）原样留在后台，回来时一秒恢复。
 */
class MaskDetailActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // 诊断日志（2026-10-31）：用户报「点恢复到系统默认壁纸后二级页会重建」。
        // 有这一行就能一眼分清：重建 = 再出现一条 onCreate；`cfg` 两次对比还能看出是哪一项变了。
        android.util.Log.i(
            "HyperExtreme",
            "DETAIL onCreate hash=${System.identityHashCode(this)} saved=${savedInstanceState != null} " +
                "cfg=${resources.configuration}",
        )
        val page = pageFrom(intent)
        // 与 MainActivity 同一套系统栏样式（不然深色主题下状态栏图标会看不见）。
        applyAppSystemBars(resolveAppDark(this, MaskPrefs(this).themeMode))
        super.onCreate(savedInstanceState)
        requestHighRefreshRate()
        setContent {
            val prefs = remember { MaskPrefs(this) }
            val themeMode = remember { ThemeMode.of(prefs.themeMode) }
            // ThemeController 的 colorSchemeMode 是 val，只能换实例来切换深浅色。
            val controller = remember(themeMode) {
                ThemeController(colorSchemeMode = themeMode.toColorSchemeMode())
            }
            // textStyles 与一级页面一致（[HyperTextStyles]，见该文件里的说明）。
            MiuixTheme(controller = controller, textStyles = HyperTextStyles) {
                MaskDetailScreen(page = page, onClose = { finish() })
            }
        }
    }

    /**
     * 系统配置变化（2026-10-31）：
     *
     * 清单里给本 Activity 声明了 `configChanges`，**系统变化不再重建 Activity** ——
     * 起因是「点恢复到系统默认壁纸后二级页整页重载」（清壁纸会让系统重算一套 config，
     * 触发 relaunch）。既然自己接住，就得把**依赖 config 的两处**手工补上：
     * ① 系统栏明暗（[applyAppSystemBars]）；② Compose 侧的 `LocalConfiguration` 由
     * `AndroidComposeView` 自己监听并更新，不用管。
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyAppSystemBars(resolveAppDark(this, MaskPrefs(this).themeMode))
    }

    companion object {
        private const val EXTRA_PAGE = "detail_page"

        /**
         * 打开某个二级页。走 `startActivity` 就是为了那条系统转场（见类注释）。
         *
         * 标 `internal` 是因为参数类型 [DetailPage] 是 internal 的 —— 这个类只在本模块内部被调用。
         */
        internal fun start(context: Context, page: DetailPage) {
            context.startActivity(
                Intent(context, MaskDetailActivity::class.java).putExtra(EXTRA_PAGE, page.name),
            )
        }

        /** 认不出来的取值（理论上不会）就退回「锁屏遮罩替换」。 */
        private fun pageFrom(intent: Intent): DetailPage =
            DetailPage.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_PAGE) }
                ?: DetailPage.MaskReplace
    }
}

/**
 * 二级页的全部内容 + 它自己的三个弹窗（尺寸不一致 / 屏幕灭亮提醒 / 选择要重启的应用）。
 *
 * 这些 2026-09-29 从 `MaskSwapShell` 整块搬过来 —— 二级页成了独立 Activity 之后，
 * 它得能自己走完「选图 → 提示 → 替换」这条链，不能再靠一级页面里的状态。
 * 搬过来的代码逻辑一字未改，只是把 `context` / `scope` / `state` 的来源换成了本页面自己的。
 */
@Composable
internal fun MaskDetailScreen(page: DetailPage, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { MaskPrefs(context) }
    val state = remember { MaskUiState(context, prefs, scope) }

    var pendingImport by remember { mutableStateOf<PendingImport?>(null) }
    // 退出动画期间 pendingImport 已经置空，但弹窗内容还要渲染 —— 用最后一份数据兜住。
    var lastPendingImport by remember { mutableStateOf<PendingImport?>(null) }
    if (pendingImport != null) lastPendingImport = pendingImport
    var askRestartAod by remember { mutableStateOf(false) }

    LaunchedEffect(state.revision) {
        state.reloadPreviews()
    }

    // 回到前台时对齐一次状态：hook 在编辑器进程里可能已经自动解除了保护（用户换了壁纸），
    // 那时只有 SharedPreferences 变了；界面不重新读一次就会永远停在"保护中"。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) state.onResume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        val role = state.pendingRole
        state.pendingRole = null
        if (uri == null || role == null) return@rememberLauncherForActivityResult

        scope.launch {
            // 兜底：从点"选择图片"到选完图之间 root 可能被撤销，没 root 就别导入（选了也不生效）。
            if (!state.ensureRoot()) return@launch
            // 相册 URI 的授权随时可能失效，先落缓存再处理。
            val cached = withContext(Dispatchers.IO) { MaskStore.cachePicked(context, uri) }
            if (cached == null) {
                AppToast.show(context, "无法读取所选图片")
                return@launch
            }
            val imageSize = withContext(Dispatchers.IO) { MaskStore.readSize(cached) }
            if (imageSize == null) {
                withContext(Dispatchers.IO) { cached.delete() }
                AppToast.show(context, "无法识别所选图片的尺寸")
                return@launch
            }
            // 对齐锚点用**当前壁纸原图**（`templates/current/wallpaper`）的尺寸，不是屏幕尺寸：
            // 替换的是抠像层，它和壁纸的像素网格对齐。
            //
            // 取值顺序：**hook 探测**（点选图时已发出请求，这里等回执，最多 5 s，期间显示
            // 我们自己的「正在准备文件」弹窗；系统相册返回时那条「正在准备文件…」进度条是系统的，
            // 两条不冲突）→ 超时就 toast「分辨率对比失败，请手动检查后再开始替换」并跳过尺寸检查。
            // null 时**跳过检查**（不弹窗），绝不再退回旧缓存乱报分辨率。
            val anchor = state.awaitWallpaperSize()
            if (anchor != null && !sameSize(imageSize, anchor)) {
                pendingImport = PendingImport(role, cached, imageSize, anchor)
            } else {
                state.importRole(role, cached)
            }
        }
    }

    /**
     * 「选图」的入口（本页两张角色卡都走它）。
     *
     * 两道闸门：① 没 root 直接拦下；② 主体 / 遮罩互斥，另一边已经选过就先提示清空，
     * 别让两边同时有图。
     */
    val pickRole: (MaskRole) -> Unit = { role ->
        scope.launch {
            if (!state.ensureRoot()) return@launch
            // 发起一次「hook 探测壁纸原图」：写请求号 + 后台重启宿主（kill → provider 拉起），
            // 让 hook 在宿主机进程里读 `templates/current/wallpaper`。结果由 awaitWallpaperSize 收取。
            state.beginWallpaperProbe()
            val other = if (role == MaskRole.SUBJECT) MaskRole.SUBJECT_MASK else MaskRole.SUBJECT
            if (state.sizes[other] != null) {
                AppToast.show(context, "已选择${other.title}，请先清空再选择")
                return@launch
            }
            state.pendingRole = role
            picker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        }
    }

    /**
     * 「视差壁纸效果」的相册拾取器 —— 与主体 / 遮罩链**独立**：
     * 不查 root、不做尺寸比对（任意比例都行，显示侧 CENTER_CROP + 额外放大），
     * 导入后即"候选就绪"，等用户点「立即替换」才在锁屏生效。
     */
    val parallaxPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val cached = withContext(Dispatchers.IO) { MaskStore.cachePicked(context, uri) }
            if (cached == null) {
                AppToast.show(context, "无法读取所选图片")
                return@launch
            }
            state.importParallaxBg(cached)
        }
    }

    /**
     * 「视差壁纸效果 → 选择图片」的入口：先做**景深检查**（用户指定：点「选择图片」时检查）。
     *
     * 没开 → toast「请先在系统锁屏编辑开启景深效果」并中止（不打开相册）；
     * 检查期间置 `busy` 防连点（按钮短暂进入禁用态，检查通常几十毫秒、冷启动路径最多 3 秒）。
     */
    val pickParallaxBg: () -> Unit = {
        scope.launch {
            if (state.busy) return@launch
            state.busy = true
            try {
                if (state.ensureDepthEnabled()) {
                    parallaxPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                }
            } finally {
                state.busy = false
            }
        }
    }

        // 底色自己铺：这一层就是整个窗口的内容，不铺会在第一帧露白（主题的 windowBackground 是白的）。
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.surface),
    ) {
        DetailTabHost(
            title = when (page) {
                DetailPage.MaskReplace -> "锁屏遮罩替换"
                DetailPage.ParallaxBg -> "视差壁纸"
                DetailPage.SpatialWallpaper -> "空间壁纸"
                DetailPage.RasterWallpaper -> "光栅壁纸"
                DetailPage.SystemUi -> "系统界面"
                DetailPage.Desktop -> "系统桌面"
            },
            onBack = onClose,
            onRestartAod = { askRestartAod = true },
        ) {
            when (page) {
                DetailPage.MaskReplace -> MaskReplaceDetail(state = state, onPick = pickRole)
                DetailPage.ParallaxBg -> ParallaxBgDetail(state = state, onPick = pickParallaxBg)
                // 「空间壁纸」：**独立链**，暂不接收选图回调（深度模型流程还没接）。
                DetailPage.SpatialWallpaper -> SpatialWallpaperDetail(state)
                // 「光栅壁纸」（2026-11-03）：目前只是**主体验证页**，渲染链还没接。
                DetailPage.RasterWallpaper -> RasterWallpaperDetail(state)
                DetailPage.SystemUi -> SystemUiDetail(state)
                DetailPage.Desktop -> DesktopDetail(state)
            }
        }
    }

    lastPendingImport?.let { pending ->
        WindowDialog(
            title = "尺寸与壁纸不一致",
            summary = "所选图片 ${pending.imageSize.first}×${pending.imageSize.second}，" +
                "壁纸 ${pending.wallpaperSize.first}×${pending.wallpaperSize.second}。\n" +
                "尺寸不一致时抠像可能错位。仍要继续吗？",
            show = pendingImport != null,
            onDismissRequest = { pendingImport = null },
        ) {
            val dismissState = LocalDismissState.current
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButtonLike(
                    text = "取消",
                    onClick = {
                        val source = pending.source
                        pendingImport = null
                        dismissState?.invoke()
                        source.delete()
                    },
                )
                Spacer(Modifier.width(20.dp))
                TextButtonLike(
                    text = "确定",
                    primary = true,
                    onClick = {
                        val source = pending.source
                        pendingImport = null
                        dismissState?.invoke()
                        scope.launch { state.importRole(pending.role, source) }
                    },
                )
            }
        }
    }

    // 「点选择图片 → hook 探测壁纸原图」时的等待提示：等 hook 回执（最多 5 秒）。
    // ⚠️ 别和系统相册自己那条「正在准备文件…100%」进度条搞混 —— 那条属于 `com.android.photopicker`，
    // 出现在相册里（准备文件时）；本弹窗是**我们自己的**，出现在从相册回来之后。
    WindowDialog(
        title = "正在准备文件",
        summary = "正在读取当前壁纸原图的分辨率，请稍候…",
        show = state.preparingWallpaper,
        onDismissRequest = {},
    ) {}

    // 顶栏右上角那个重启按钮：弹窗本身与一级页面共用 [RestartDialog]。
    RestartDialog(
        show = askRestartAod,
        onDismissRequest = { askRestartAod = false },
        onConfirm = { state.restartTargets(it) },
    )

    // 「屏幕灭亮提醒」弹窗已于 2026-10-04 按用户要求删除 —— 注入改成 hook 后不再依赖灭亮屏，
    // 是否灭亮屏改由「替换行为 → 替换后自动灭亮屏一次」开关决定（见 MaskReplaceDetail）。
}

/** 等待用户确认"分辨率不一致"的替换请求。（2026-09-29 从 `MaskSwapScreen.kt` 搬来：只有本页用。） */
internal data class PendingImport(
    val role: MaskRole,
    val source: File,
    val imageSize: Pair<Int, Int>,
    /** 当前壁纸的尺寸 —— 对齐锚点，不是屏幕尺寸。 */
    val wallpaperSize: Pair<Int, Int>,
)

/** 图片和锚点尺寸一致（含旋转 90°）就算匹配。 */
internal fun sameSize(image: Pair<Int, Int>, anchor: Pair<Int, Int>): Boolean =
    (image.first == anchor.first && image.second == anchor.second) ||
        (image.first == anchor.second && image.second == anchor.first)
