package com.resukisu.resukisu.ui.theme

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.zIndex
import androidx.core.content.edit
import androidx.core.net.toUri
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import com.resukisu.resukisu.ui.theme.util.BackgroundTransformation
import com.resukisu.resukisu.ui.theme.util.saveTransformedBackground
import com.resukisu.resukisu.ui.webui.MonetColorsProvider
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import java.io.File
import java.io.FileOutputStream

// ==================== CompositionLocals (与 SukiSU 一致) ====================

val LocalColorMode = staticCompositionLocalOf { 0 }

// ==================== ThemeConfig ====================

@Stable
object ThemeConfig {
    // 主题模式: 0=System, 1=Light, 2=Dark, 3=MonetSystem, 4=MonetLight, 5=MonetDark
    var colorMode by mutableIntStateOf(0)
    var keyColorInt by mutableIntStateOf(0) // 0 = 使用默认色

    // 背景状态
    var customBackgroundUri by mutableStateOf<Uri?>(null)
    var backgroundDim by mutableFloatStateOf(0f)
    var backgroundImageLoaded by mutableStateOf(false)
    var isThemeChanging by mutableStateOf(false)
    var preventBackgroundRefresh by mutableStateOf(false)

    // 主题变化检测
    private var lastDarkModeState: Boolean? = null

    fun detectThemeChange(currentDarkMode: Boolean): Boolean {
        val hasChanged = lastDarkModeState != null && lastDarkModeState != currentDarkMode
        lastDarkModeState = currentDarkMode
        return hasChanged
    }

    fun resetBackgroundState() {
        if (!preventBackgroundRefresh) {
            backgroundImageLoaded = false
        }
        isThemeChanging = true
    }

    fun reset() {
        colorMode = 0
        keyColorInt = 0
        customBackgroundUri = null
        backgroundDim = 0f
        backgroundImageLoaded = false
        isThemeChanging = false
        preventBackgroundRefresh = false
        lastDarkModeState = null
    }
}

// ==================== ThemeManager ====================

object ThemeManager {
    private const val PREFS_NAME = "theme_prefs"

    fun saveColorMode(context: Context, mode: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putInt("color_mode", mode)
        }
        ThemeConfig.colorMode = mode
    }

    fun loadColorMode(context: Context) {
        ThemeConfig.colorMode = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt("color_mode", 0)
    }

    fun saveKeyColor(context: Context, colorInt: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putInt("key_color", colorInt)
        }
        ThemeConfig.keyColorInt = colorInt
    }

    fun loadKeyColor(context: Context) {
        ThemeConfig.keyColorInt = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt("key_color", 0)
    }
}

// ==================== BackgroundManager ====================

object BackgroundManager {
    private const val TAG = "BackgroundManager"

    fun saveBackgroundDim(context: Context, dim: Float) {
        ThemeConfig.backgroundDim = dim
        context.getSharedPreferences("theme_prefs", Context.MODE_PRIVATE).edit(commit = true) {
            putFloat("background_dim", dim)
        }
    }

    fun saveAndApplyCustomBackground(
        context: Context,
        uri: Uri,
        transformation: BackgroundTransformation? = null
    ) {
        try {
            val finalUri = if (transformation != null) {
                context.saveTransformedBackground(uri, transformation)
            } else {
                copyImageToInternalStorage(context, uri)
            }

            saveBackgroundUri(context, finalUri)
            ThemeConfig.customBackgroundUri = finalUri
            CardConfig.updateBackground(true)
            resetBackgroundState(context)

        } catch (e: Exception) {
            Log.e(TAG, "保存背景失败: ${e.message}", e)
        }
    }

    fun clearCustomBackground(context: Context) {
        saveBackgroundUri(context, null)
        ThemeConfig.customBackgroundUri = null
        CardConfig.updateBackground(false)
        resetBackgroundState(context)
    }

    fun loadCustomBackground(context: Context) {
        val prefs = context.getSharedPreferences("theme_prefs", Context.MODE_PRIVATE)
        val uriString = prefs.getString("custom_background", null)

        val newUri = uriString?.toUri()
        val preventRefresh = prefs.getBoolean("prevent_background_refresh", false)

        ThemeConfig.preventBackgroundRefresh = preventRefresh

        if (!preventRefresh || ThemeConfig.customBackgroundUri?.toString() != newUri?.toString()) {
            Log.d(TAG, "加载自定义背景: $uriString")
            ThemeConfig.customBackgroundUri = newUri
            ThemeConfig.backgroundImageLoaded = false
            CardConfig.updateBackground(newUri != null)
        }

        ThemeConfig.backgroundDim = prefs.getFloat("background_dim", 0f).coerceIn(0f, 1f)
    }

    private fun saveBackgroundUri(context: Context, uri: Uri?) {
        context.getSharedPreferences("theme_prefs", Context.MODE_PRIVATE).edit {
            putString("custom_background", uri?.toString())
            putBoolean("prevent_background_refresh", false)
        }
    }

    private fun resetBackgroundState(context: Context) {
        ThemeConfig.backgroundImageLoaded = false
        ThemeConfig.preventBackgroundRefresh = false
        context.getSharedPreferences("theme_prefs", Context.MODE_PRIVATE).edit {
            putBoolean("prevent_background_refresh", false)
        }
    }

    private fun copyImageToInternalStorage(context: Context, uri: Uri): Uri? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val fileName = "custom_background.jpg"
            val file = File(context.filesDir, fileName)

            FileOutputStream(file).use { outputStream ->
                val buffer = ByteArray(8 * 1024)
                var read: Int
                while (inputStream.read(buffer).also { read = it } != -1) {
                    outputStream.write(buffer, 0, read)
                }
                outputStream.flush()
            }
            inputStream.close()

            Uri.fromFile(file)
        } catch (e: Exception) {
            Log.e(TAG, "复制图片失败: ${e.message}", e)
            null
        }
    }
}

// ==================== KernelSUTheme (与 SukiSU 一致的纯 Miuix 方案) ====================

@Composable
fun KernelSUTheme(
    colorMode: Int = ThemeConfig.colorMode,
    keyColor: Color? = remember(ThemeConfig.keyColorInt) {
        if (ThemeConfig.keyColorInt == 0) null else Color(ThemeConfig.keyColorInt)
    },
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val systemIsDark = isSystemInDarkTheme()
    val darkTheme = isInDarkTheme()

    // 初始化主题
    ThemeInitializer(context = context, systemIsDark = systemIsDark)

    // 系统栏样式
    SystemBarController(darkTheme)

    // 与 SukiSU 完全一致的 ThemeController 创建
    val controller = when (colorMode) {
        1 -> ThemeController(ColorSchemeMode.Light)
        2 -> ThemeController(ColorSchemeMode.Dark)
        3 -> ThemeController(
            ColorSchemeMode.MonetSystem,
            keyColor = keyColor,
            isDark = systemIsDark
        )
        4 -> ThemeController(
            ColorSchemeMode.MonetLight,
            keyColor = keyColor,
        )
        5 -> ThemeController(
            ColorSchemeMode.MonetDark,
            keyColor = keyColor,
        )
        else -> ThemeController(ColorSchemeMode.System)
    }

    MiuixTheme(
        controller = controller,
        content = {
            // M3 桥接：让现有 MaterialTheme.colorScheme 引用继续工作
            val bridgedColorScheme = miuixToM3ColorScheme(darkTheme)
            MaterialTheme(colorScheme = bridgedColorScheme) {
                MonetColorsProvider.UpdateCss()
                Box(modifier = Modifier.fillMaxSize()) {
                    BackgroundLayer(darkTheme)
                    content()
                }
            }
        }
    )
}

// ==================== isInDarkTheme (与 SukiSU 一致) ====================

@Composable
@ReadOnlyComposable
fun isInDarkTheme(): Boolean {
    return when (ThemeConfig.colorMode) {
        1, 4 -> false  // 强制浅色
        2, 5 -> true   // 强制深色
        else -> isSystemInDarkTheme()  // 跟随系统 (0, 3)
    }
}

// ==================== M3 桥接函数 ====================

@Composable
private fun miuixToM3ColorScheme(darkTheme: Boolean): ColorScheme {
    val miuix = MiuixTheme.colorScheme
    return if (darkTheme) {
        darkColorScheme(
            primary = miuix.primary,
            onPrimary = miuix.onPrimary,
            primaryContainer = miuix.primaryContainer,
            onPrimaryContainer = miuix.onPrimaryContainer,
            secondary = miuix.secondary,
            onSecondary = miuix.onSecondary,
            secondaryContainer = miuix.secondaryContainer,
            onSecondaryContainer = miuix.onSecondaryContainer,
            tertiaryContainer = miuix.tertiaryContainer,
            onTertiaryContainer = miuix.onTertiaryContainer,
            error = miuix.error,
            onError = miuix.onError,
            errorContainer = miuix.errorContainer,
            onErrorContainer = miuix.onErrorContainer,
            background = if (CardConfig.isCustomBackgroundEnabled) Color.Transparent else miuix.background,
            onBackground = miuix.onBackground,
            surface = if (CardConfig.isCustomBackgroundEnabled) Color.Transparent else miuix.surface,
            onSurface = miuix.onSurface,
            surfaceVariant = miuix.surfaceVariant,
            onSurfaceVariant = miuix.onSurfaceSecondary,
            outline = miuix.outline,
            outlineVariant = miuix.dividerLine,
            surfaceContainer = miuix.surfaceContainer,
            surfaceContainerHigh = miuix.surfaceContainerHigh,
            surfaceContainerHighest = miuix.surfaceContainerHighest,
        )
    } else {
        lightColorScheme(
            primary = miuix.primary,
            onPrimary = miuix.onPrimary,
            primaryContainer = miuix.primaryContainer,
            onPrimaryContainer = miuix.onPrimaryContainer,
            secondary = miuix.secondary,
            onSecondary = miuix.onSecondary,
            secondaryContainer = miuix.secondaryContainer,
            onSecondaryContainer = miuix.onSecondaryContainer,
            tertiaryContainer = miuix.tertiaryContainer,
            onTertiaryContainer = miuix.onTertiaryContainer,
            error = miuix.error,
            onError = miuix.onError,
            errorContainer = miuix.errorContainer,
            onErrorContainer = miuix.onErrorContainer,
            background = if (CardConfig.isCustomBackgroundEnabled) Color.Transparent else miuix.background,
            onBackground = miuix.onBackground,
            surface = if (CardConfig.isCustomBackgroundEnabled) Color.Transparent else miuix.surface,
            onSurface = miuix.onSurface,
            surfaceVariant = miuix.surfaceVariant,
            onSurfaceVariant = miuix.onSurfaceSecondary,
            outline = miuix.outline,
            outlineVariant = miuix.dividerLine,
            surfaceContainer = miuix.surfaceContainer,
            surfaceContainerHigh = miuix.surfaceContainerHigh,
            surfaceContainerHighest = miuix.surfaceContainerHighest,
        )
    }
}

// ==================== ThemeInitializer ====================

@Composable
private fun ThemeInitializer(context: Context, systemIsDark: Boolean) {
    val themeChanged = ThemeConfig.detectThemeChange(systemIsDark)
    val scope = rememberCoroutineScope()

    // 处理系统主题变化
    LaunchedEffect(systemIsDark, themeChanged) {
        // colorMode 0 或 3 时跟随系统
        if (ThemeConfig.colorMode in listOf(0, 3) && themeChanged) {
            Log.d("ThemeSystem", "系统主题变化: $systemIsDark")
            ThemeConfig.resetBackgroundState()

            if (!ThemeConfig.preventBackgroundRefresh) {
                BackgroundManager.loadCustomBackground(context)
            }

            CardConfig.apply {
                load(context)
                setThemeDefaults(systemIsDark)
                save(context)
            }
        }
    }

    // 初始加载配置
    LaunchedEffect(Unit) {
        scope.launch {
            ThemeManager.loadColorMode(context)
            ThemeManager.loadKeyColor(context)
            CardConfig.load(context)

            if (!ThemeConfig.backgroundImageLoaded && !ThemeConfig.preventBackgroundRefresh) {
                BackgroundManager.loadCustomBackground(context)
            }
        }
    }
}

// ==================== BackgroundLayer ====================

@Composable
private fun BackgroundLayer(darkTheme: Boolean) {
    val backgroundUri = rememberSaveable { mutableStateOf(ThemeConfig.customBackgroundUri) }

    LaunchedEffect(ThemeConfig.customBackgroundUri) {
        backgroundUri.value = ThemeConfig.customBackgroundUri
    }

    // 默认背景
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(-2f)
            .background(
                MaterialTheme.colorScheme.surfaceContainer
            )
    )

    // 自定义背景
    backgroundUri.value?.let { uri ->
        CustomBackgroundLayer(uri = uri, darkTheme = darkTheme)
    }
}

@Composable
private fun CustomBackgroundLayer(uri: Uri, darkTheme: Boolean) {
    val painter = rememberAsyncImagePainter(
        model = uri,
        onError = { error ->
            Log.e("ThemeSystem", "背景加载失败: ${error.result.throwable.message}")
            ThemeConfig.customBackgroundUri = null
        },
        onSuccess = {
            Log.d("ThemeSystem", "背景加载成功")
            ThemeConfig.backgroundImageLoaded = true
            ThemeConfig.isThemeChanging = false
        }
    )

    val transition = updateTransition(
        targetState = ThemeConfig.backgroundImageLoaded,
        label = "backgroundTransition"
    )

    val alpha by transition.animateFloat(
        label = "backgroundAlpha",
        transitionSpec = {
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            )
        }
    ) { loaded -> if (loaded) 1f else 0f }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(-1f)
            .alpha(alpha)
    ) {
        val surfaceContainer = MaterialTheme.colorScheme.surfaceContainer
        Box(
            modifier = Modifier
                .fillMaxSize()
                .paint(
                    painter = painter,
                    contentScale = ContentScale.Crop,
                )
                .graphicsLayer {
                    this.alpha =
                        (painter.state as? AsyncImagePainter.State.Success)?.let { 1f } ?: 0f
                }
                .drawWithContent {
                    drawContent()
                    drawRect(color = surfaceContainer.copy(alpha = ThemeConfig.backgroundDim))
                }
        )
    }
}

// ==================== SystemBarController ====================

@Composable
private fun SystemBarController(darkMode: Boolean) {
    val context = LocalContext.current
    val activity = context as ComponentActivity

    SideEffect {
        activity.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                Color.Transparent.toArgb(),
                Color.Transparent.toArgb(),
            ) { darkMode },
            navigationBarStyle = if (darkMode) {
                SystemBarStyle.dark(Color.Transparent.toArgb())
            } else {
                SystemBarStyle.light(
                    Color.Transparent.toArgb(),
                    Color.Transparent.toArgb()
                )
            }
        )
    }
}

// ==================== Context 扩展函数 ====================

@OptIn(DelicateCoroutinesApi::class)
fun Context.saveAndApplyCustomBackground(uri: Uri, transformation: BackgroundTransformation? = null) {
    kotlinx.coroutines.GlobalScope.launch {
        BackgroundManager.saveAndApplyCustomBackground(this@saveAndApplyCustomBackground, uri, transformation)
    }
}

fun Context.saveCustomBackground(uri: Uri?) {
    if (uri != null) {
        saveAndApplyCustomBackground(uri)
    } else {
        BackgroundManager.clearCustomBackground(this)
    }
}

fun Context.saveColorMode(mode: Int) {
    ThemeManager.saveColorMode(this, mode)
}

fun Context.saveKeyColor(colorInt: Int) {
    ThemeManager.saveKeyColor(this, colorInt)
}
