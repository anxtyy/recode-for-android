package com.anxtyy.recode

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val storage = remember { AppStorage(context) }
            var settings by remember { mutableStateOf(storage.loadSettings()) }
            var serverModels by remember { mutableStateOf(storage.loadServerModels()) }

            // При старте убеждаемся, что папка Project создана в выбранной директории
            LaunchedEffect(settings.workspaceUri) {
                if (settings.workspaceUri.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        AiVibeEngine.ensureProjectRootFolder(context, Uri.parse(settings.workspaceUri))
                    }
                }
            }

            ReCodeTheme(settings = settings) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (!settings.onboardingCompleted || !settings.isLoggedIn) {
                        OnboardingScreen(
                            settings = settings,
                            onUpdateSettings = { updated ->
                                settings = updated
                                storage.saveSettings(updated)
                            }
                        )
                    } else {
                        MainVibeCodingApp(
                            settings = settings,
                            serverModels = serverModels,
                            storage = storage,
                            onUpdateSettings = { updated ->
                                settings = updated
                                storage.saveSettings(updated)
                            },
                            onUpdateServerModels = { newModels ->
                                serverModels = newModels
                                storage.saveServerModels(newModels)
                            }
                        )
                    }
                }
            }
        }
    }
}

// ============================================================================
// МОДЕЛИ ДАННЫХ, ПРОЕКТЫ И ПРОВАЙДЕРЫ API
// ============================================================================

enum class AppColorTheme(val title: String, val primaryColor: Color) {
    DYNAMIC("Системная (Адаптивная)", Color(0xFFBAC3FF)),
    RECODE_RED("Re:Code Crimson", Color(0xFFFF2A2A)),
    CYBER_VIOLET("Cyber Violet", Color(0xFF9C27B0)),
    EMERALD_CODE("Matrix Emerald", Color(0xFF00E676)),
    OCEAN_BLUE("DeepSeek Ocean", Color(0xFF3B82F6))
}

enum class AppFontChoice(val title: String, val fontFamily: FontFamily) {
    SYSTEM("Системный (Sans)", FontFamily.SansSerif),
    MONO("Кодерский (Monospace)", FontFamily.Monospace),
    SERIF("Академический (Serif)", FontFamily.Serif),
    CURSIVE("Кастомный (Default)", FontFamily.Default)
}

enum class ApiProviderType(val displayName: String, val hintPrefix: String) {
    AUTO("Автоопределение по ключу", "AQ... / sk-..."),
    GOOGLE_GEMINI("Google Gemini API", "AQ... / AIza..."),
    DEEPSEEK("DeepSeek API", "sk-5fff5..."),
    OPENROUTER("OpenRouter API", "sk-or-..."),
    OPENAI("OpenAI / Custom API", "sk-...")
}

fun resolveProvider(selected: ApiProviderType, rawKey: String): ApiProviderType {
    if (selected != ApiProviderType.AUTO) return selected
    val clean = rawKey.trim()
    val deepSeekRegex = Regex("^sk-[0-9a-fA-F]{20,48}$")
    return when {
        clean.startsWith("AQ") || clean.startsWith("AIza") -> ApiProviderType.GOOGLE_GEMINI
        clean.startsWith("sk-or-") -> ApiProviderType.OPENROUTER
        deepSeekRegex.matches(clean) -> ApiProviderType.DEEPSEEK
        else -> ApiProviderType.OPENAI
    }
}

fun isKeyFormatPlausible(rawKey: String): Boolean {
    val clean = rawKey.trim()
    if (clean.length < 16 || clean.contains(" ")) return false
    return clean.startsWith("AQ") ||
            clean.startsWith("AIza") ||
            clean.startsWith("sk-") ||
            clean.length >= 24
}

// Сокращение длинного названия модели для нижней панели (например, "Deep Research Pre...")
fun formatShortModelName(name: String, maxChars: Int = 18): String {
    val trimmed = name.trim()
    return if (trimmed.length > maxChars) {
        trimmed.take(maxChars).trimEnd() + "..."
    } else {
        trimmed
    }
}

data class AiModelInfo(
    val id: String,
    val displayName: String,
    val badge: String,
    val provider: ApiProviderType,
    val description: String,
    val isFree: Boolean = false
)

enum class ModelFilterCategory(val title: String) {
    ALL("Все"),
    CODER_PRO("Кодерские / Топ"),
    REASONING("Рассуждающие"),
    FREE("Бесплатные")
}

data class GeneratedFile(
    val path: String,
    val content: String,
    val savedToDisk: Boolean = false
)

data class VibeProject(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val files: List<GeneratedFile> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val isUser: Boolean,
    val text: String,
    val reasoningText: String = "",
    val modelName: String = "",
    val files: List<GeneratedFile> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
)

data class ChatSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val messages: List<ChatMessage> = emptyList(),
    val isPinned: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)

data class AppSettings(
    val onboardingCompleted: Boolean = false,
    val workspaceUri: String = "",
    val selectedModelId: String = "",
    val colorTheme: AppColorTheme = AppColorTheme.DYNAMIC,
    val darkMode: Boolean = true,
    val fontScale: Float = 1.0f,
    val fontChoice: AppFontChoice = AppFontChoice.SYSTEM,
    val reasoningEnabled: Boolean = true,
    val autoWriteFiles: Boolean = true,
    val apiKey: String = "",
    val preferredProvider: ApiProviderType = ApiProviderType.AUTO,
    val customBaseUrl: String = "https://api.openai.com/v1",
    val userName: String = "Разработчик",
    val isLoggedIn: Boolean = false
)

// ============================================================================
// ТЕМА И МАСШТАБИРОВАНИЕ ТЕКСТА / ШРИФТА
// ============================================================================

@Composable
fun ReCodeTheme(
    settings: AppSettings,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        settings.colorTheme == AppColorTheme.DYNAMIC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (settings.darkMode) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        settings.darkMode -> darkColorScheme(
            primary = settings.colorTheme.primaryColor,
            secondary = settings.colorTheme.primaryColor.copy(alpha = 0.8f),
            tertiary = Color(0xFFFF5252),
            background = Color(0xFF0E0F12),
            surface = Color(0xFF17181C),
            surfaceVariant = Color(0xFF23252C)
        )
        else -> lightColorScheme(
            primary = settings.colorTheme.primaryColor,
            secondary = settings.colorTheme.primaryColor.copy(alpha = 0.8f),
            background = Color(0xFFF7F8FA),
            surface = Color(0xFFFFFFFF),
            surfaceVariant = Color(0xFFEAECEF)
        )
    }

    val baseTypography = Typography()
    val customFont = settings.fontChoice.fontFamily
    val customTypography = Typography(
        displayLarge = baseTypography.displayLarge.copy(fontFamily = customFont),
        headlineLarge = baseTypography.headlineLarge.copy(fontFamily = customFont),
        headlineMedium = baseTypography.headlineMedium.copy(fontFamily = customFont),
        titleLarge = baseTypography.titleLarge.copy(fontFamily = customFont),
        titleMedium = baseTypography.titleMedium.copy(fontFamily = customFont),
        bodyLarge = baseTypography.bodyLarge.copy(fontFamily = customFont),
        bodyMedium = baseTypography.bodyMedium.copy(fontFamily = customFont),
        bodySmall = baseTypography.bodySmall.copy(fontFamily = customFont),
        labelLarge = baseTypography.labelLarge.copy(fontFamily = customFont)
    )

    val currentDensity = LocalDensity.current
    val scaledDensity = remember(settings.fontScale, currentDensity) {
        Density(currentDensity.density, currentDensity.fontScale * settings.fontScale)
    }

    CompositionLocalProvider(LocalDensity provides scaledDensity) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = customTypography,
            content = content
        )
    }
}

// ============================================================================
// ФИРМЕННЫЙ ЛОГОТИП RE:CODE
// ============================================================================

@Composable
fun ReCodeLogo(
    modifier: Modifier = Modifier,
    fontSizeSp: Int = 34,
    showFullText: Boolean = false
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = buildAnnotatedString {
                withStyle(
                    SpanStyle(
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                ) {
                    append("RE")
                }
            },
            fontSize = fontSizeSp.sp,
            letterSpacing = (-1.5).sp
        )
        Spacer(modifier = Modifier.width((fontSizeSp * 0.08f).dp))
        Column(
            verticalArrangement = Arrangement.spacedBy((fontSizeSp * 0.14f).dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val dotSize = (fontSizeSp * 0.25f).dp
            Box(
                modifier = Modifier
                    .size(dotSize)
                    .clip(CircleShape)
                    .background(Color(0xFFFF1111))
            )
            Box(
                modifier = Modifier
                    .size(dotSize)
                    .clip(CircleShape)
                    .background(Color(0xFFFF1111))
            )
        }
        if (showFullText) {
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "code",
                fontSize = (fontSizeSp * 0.85f).sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

// ============================================================================
// 1. ПОЛНЫЙ ПАРСЕР И РЕНДЕРИНГ MARKDOWN (ЖИРНЫЙ, ЗАГОЛОВКИ ###, КОД, СПИСКИ)
// ============================================================================

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier
) {
    val lines = remember(markdown) { markdown.replace("\r\n", "\n").split("\n") }
    val primaryColor = MaterialTheme.colorScheme.primary
    val codeBgColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        var inCodeBlock = false
        val codeBlockBuffer = StringBuilder()

        for (rawLine in lines) {
            val trimmed = rawLine.trimEnd()

            // Обработка многострочных кодовых блоков ```
            if (trimmed.trimStart().startsWith("```")) {
                if (inCodeBlock) {
                    Surface(
                        color = Color(0xFF0B0C0E),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = codeBlockBuffer.toString().trimEnd(),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = Color(0xFFE0E6ED),
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                    codeBlockBuffer.clear()
                    inCodeBlock = false
                } else {
                    inCodeBlock = true
                }
                continue
            }

            if (inCodeBlock) {
                codeBlockBuffer.appendLine(rawLine)
                continue
            }

            if (trimmed.isBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                continue
            }

            // Проверка на заголовки (#, ##, ###, #### — даже без пробела вроде ###Заголовок)
            val headingMatch = Regex("^(#{1,6})\\s*(.+)$").find(trimmed.trimStart())
            if (headingMatch != null) {
                val level = headingMatch.groupValues[1].length
                val headingContent = headingMatch.groupValues[2]
                val fontSize = when (level) {
                    1 -> 20.sp
                    2 -> 18.sp
                    3 -> 16.sp
                    else -> 15.sp
                }
                Text(
                    text = parseInlineMarkdown(headingContent, primaryColor, codeBgColor),
                    fontSize = fontSize,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                )
                continue
            }

            // Горизонтальный разделитель --- или ***
            if (trimmed.matches(Regex("^(-{3,}|\\*{3,})$"))) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                continue
            }

            // Маркированные списки (- , * , • )
            val bulletMatch = Regex("^(\\s*)[-*•]\\s+(.+)$").find(trimmed)
            if (bulletMatch != null) {
                val content = bulletMatch.groupValues[2]
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = "•  ",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = primaryColor
                    )
                    Text(
                        text = parseInlineMarkdown(content, primaryColor, codeBgColor),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                }
                continue
            }

            // Нумерованные списки (1. , 2. и т.д.)
            val numberedMatch = Regex("^(\\s*)(\\d+\\.)\\s+(.+)$").find(trimmed)
            if (numberedMatch != null) {
                val num = numberedMatch.groupValues[2]
                val content = numberedMatch.groupValues[3]
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = "$num ",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = primaryColor
                    )
                    Text(
                        text = parseInlineMarkdown(content, primaryColor, codeBgColor),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                }
                continue
            }

            // Обычный абзац с поддержкой **жирного**, *курсива*, `кода` и ~~зачеркнутого~~
            Text(
                text = parseInlineMarkdown(trimmed, primaryColor, codeBgColor),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

fun parseInlineMarkdown(
    text: String,
    primaryColor: Color,
    codeBgColor: Color
): AnnotatedString {
    return buildAnnotatedString {
        // Ищем **жирный**, __жирный__, `код`, ~~зачеркнутый~~, *курсив*
        val tokenRegex = Regex("(\\*\\*[^*]+\\*\\*|__[^_]+__|`[^`]+`|~~[^~]+~~|\\*[^*]+\\*)")
        var lastIndex = 0
        for (match in tokenRegex.findAll(text)) {
            if (match.range.first > lastIndex) {
                append(text.substring(lastIndex, match.range.first))
            }
            val token = match.value
            when {
                (token.startsWith("**") && token.endsWith("**")) ||
                        (token.startsWith("__") && token.endsWith("__")) -> {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(token.substring(2, token.length - 2))
                    }
                }
                token.startsWith("`") && token.endsWith("`") -> {
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            color = primaryColor,
                            background = codeBgColor
                        )
                    ) {
                        append(" " + token.substring(1, token.length - 1) + " ")
                    }
                }
                token.startsWith("~~") && token.endsWith("~~") -> {
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                        append(token.substring(2, token.length - 2))
                    }
                }
                token.startsWith("*") && token.endsWith("*") -> {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(token.substring(1, token.length - 1))
                    }
                }
                else -> append(token)
            }
            lastIndex = match.range.last + 1
        }
        if (lastIndex < text.length) {
            append(text.substring(lastIndex))
        }
    }
}

// ============================================================================
// 2. ЭКРАН ОНБОРДИНГА И ЛОКАЛЬНОГО ВХОДА ПРИ ПЕРВОМ СТАРТЕ (БЕЗ GOOGLE)
// ============================================================================

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(
    settings: AppSettings,
    onUpdateSettings: (AppSettings) -> Unit
) {
    val context = LocalContext.current
    val pagerState = rememberPagerState(pageCount = { 3 })
    val scope = rememberCoroutineScope()

    var nicknameInput by remember(settings.userName) {
        mutableStateOf(if (settings.userName == "Разработчик") "" else settings.userName)
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
            // Сразу создаём папку Project в выбранной корневой директории
            scope.launch(Dispatchers.IO) {
                AiVibeEngine.ensureProjectRootFolder(context, uri)
            }
            onUpdateSettings(settings.copy(workspaceUri = uri.toString()))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            contentAlignment = Alignment.Center
        ) {
            if (pagerState.currentPage == 2) {
                Text(
                    text = "Добро Пожаловать! Начнем?",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ReCodeLogo(fontSizeSp = 24, showFullText = true)
                    TextButton(onClick = {
                        scope.launch { pagerState.animateScrollToPage(2) }
                    }) {
                        Text("Пропустить")
                    }
                }
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f)
        ) { page ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                OnboardingIllustration(page = page)

                Spacer(modifier = Modifier.height(24.dp))

                when (page) {
                    0 -> {
                        Text(
                            text = "Автономный Вайб-Кодинг",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Опиши идею проекта в чате — ИИ в re:code самостоятельно спроектирует структуру, напишет полный рабочий код и сохранит готовые файлы в папку Project.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                    1 -> {
                        Text(
                            text = "Прямой доступ к ИИ по API",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Приложение само запрашивает у сервера актуальный список доступных моделей. Подключи свой ключ Google Gemini (AQ...), DeepSeek (sk-...), OpenRouter или OpenAI — и работай с проектами прямо из меню.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                    2 -> {
                        Text(
                            text = "Профиль и рабочая папка",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Укажи своё имя и выбери папку на устройстве — в ней автоматически создастся директория Project для твоих проектов.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        // Локальный вход без серверов
                        OutlinedTextField(
                            value = nicknameInput,
                            onValueChange = { nicknameInput = it },
                            label = { Text("Твоё имя / Никнейм") },
                            placeholder = { Text("Например: Anxtyy") },
                            leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                            singleLine = true,
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        val hasFolder = settings.workspaceUri.isNotEmpty()
                        OutlinedCard(
                            onClick = { folderPickerLauncher.launch(null) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(16.dp)
                                    .fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (hasFolder) Icons.Default.FolderSpecial else Icons.Default.CreateNewFolder,
                                    contentDescription = null,
                                    tint = if (hasFolder) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(32.dp)
                                )
                                Spacer(modifier = Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (hasFolder) "Папка выбрана (Project создана)" else "Выбрать корневую папку",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = if (hasFolder) Uri.parse(settings.workspaceUri).lastPathSegment ?: settings.workspaceUri
                                        else "Нажми, чтобы выдать разрешение к директории",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Icon(
                                    imageVector = if (hasFolder) Icons.Default.CheckCircle else Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = if (hasFolder) Color(0xFF00E676) else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            repeat(3) { index ->
                val active = pagerState.currentPage == index
                Box(
                    modifier = Modifier
                        .height(8.dp)
                        .width(if (active) 28.dp else 8.dp)
                        .clip(CircleShape)
                        .background(
                            if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                )
            }
        }

        Button(
            onClick = {
                if (pagerState.currentPage < 2) {
                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                } else {
                    val cleanName = nicknameInput.trim()
                    if (cleanName.isEmpty()) {
                        Toast.makeText(context, "Введи своё имя или никнейм для входа!", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    if (settings.workspaceUri.isEmpty()) {
                        Toast.makeText(
                            context,
                            "Пожалуйста, выбери папку для создания директории Project!",
                            Toast.LENGTH_SHORT
                        ).show()
                        folderPickerLauncher.launch(null)
                    } else {
                        onUpdateSettings(
                            settings.copy(
                                userName = cleanName,
                                isLoggedIn = true,
                                onboardingCompleted = true
                            )
                        )
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(18.dp)
        ) {
            Text(
                text = if (pagerState.currentPage == 2) "Начать" else "Далее",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun OnboardingIllustration(page: Int) {
    val primary = MaterialTheme.colorScheme.primary
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant

    Box(
        modifier = Modifier
            .size(190.dp)
            .clip(RoundedCornerShape(40.dp))
            .background(surfaceVariant.copy(alpha = 0.6f))
            .border(1.dp, primary.copy(alpha = 0.25f), RoundedCornerShape(40.dp)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(125.dp)) {
            val w = size.width
            val h = size.height
            when (page) {
                0 -> {
                    drawRoundRect(
                        color = primary.copy(alpha = 0.2f),
                        size = size,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(28f, 28f)
                    )
                    drawLine(
                        color = primary,
                        start = Offset(w * 0.25f, h * 0.35f),
                        end = Offset(w * 0.75f, h * 0.35f),
                        strokeWidth = 10f,
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = Color(0xFFFF2A2A),
                        start = Offset(w * 0.25f, h * 0.55f),
                        end = Offset(w * 0.6f, h * 0.55f),
                        strokeWidth = 10f,
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = primary.copy(alpha = 0.7f),
                        start = Offset(w * 0.25f, h * 0.75f),
                        end = Offset(w * 0.8f, h * 0.75f),
                        strokeWidth = 10f,
                        cap = StrokeCap.Round
                    )
                }
                1 -> {
                    val center = Offset(w / 2f, h / 2f)
                    drawCircle(color = primary.copy(alpha = 0.2f), radius = w * 0.48f, center = center)
                    drawCircle(color = primary, radius = 16f, center = Offset(w * 0.25f, h * 0.3f))
                    drawCircle(color = Color(0xFFFF2A2A), radius = 16f, center = Offset(w * 0.75f, h * 0.3f))
                    drawCircle(color = primary, radius = 20f, center = Offset(w * 0.5f, h * 0.75f))
                    drawLine(primary, Offset(w * 0.25f, h * 0.3f), Offset(w * 0.75f, h * 0.3f), strokeWidth = 6f)
                    drawLine(primary, Offset(w * 0.25f, h * 0.3f), Offset(w * 0.5f, h * 0.75f), strokeWidth = 6f)
                    drawLine(Color(0xFFFF2A2A), Offset(w * 0.75f, h * 0.3f), Offset(w * 0.5f, h * 0.75f), strokeWidth = 6f)
                }
                2 -> {
                    drawRoundRect(
                        color = primary.copy(alpha = 0.25f),
                        topLeft = Offset(w * 0.1f, h * 0.25f),
                        size = androidx.compose.ui.geometry.Size(w * 0.8f, h * 0.55f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(24f, 24f)
                    )
                    drawCircle(
                        color = Color(0xFF00E676),
                        radius = 24f,
                        center = Offset(w * 0.5f, h * 0.52f)
                    )
                }
            }
        }
    }
}

// ============================================================================
// 3. ГЛАВНЫЙ ЭКРАН ПРИЛОЖЕНИЯ (СКРЫТИЕ КЛАВИАТУРЫ, ЗАКРЕП ЧАТОВ, ПРОЕКТЫ)
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainVibeCodingApp(
    settings: AppSettings,
    serverModels: List<AiModelInfo>,
    storage: AppStorage,
    onUpdateSettings: (AppSettings) -> Unit,
    onUpdateServerModels: (List<AiModelInfo>) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // Автоматически прячем клавиатуру при открытии бокового меню (кнопкой или свайпом)
    LaunchedEffect(drawerState.currentValue, drawerState.targetValue) {
        if (drawerState.isOpen || drawerState.targetValue == DrawerValue.Open) {
            focusManager.clearFocus()
            keyboardController?.hide()
        }
    }

    var sessions by remember { mutableStateOf(storage.loadSessions()) }
    var projects by remember { mutableStateOf(storage.loadProjects()) }

    var currentSessionId by remember {
        mutableStateOf(sessions.firstOrNull()?.id ?: createNewSession().also { sessions = listOf(it) }.id)
    }
    var searchQuery by remember { mutableStateOf("") }
    var inputPrompt by remember { mutableStateOf("") }
    var isGenerating by remember { mutableStateOf(false) }

    var showModelPickerSheet by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var previewHtmlFile by remember { mutableStateOf<GeneratedFile?>(null) }

    // Диалоги для работы с разделом "Проекты"
    var fileToAddToProject by remember { mutableStateOf<GeneratedFile?>(null) }
    var showCreateProjectDialog by remember { mutableStateOf(false) }
    var projectForAiAction by remember { mutableStateOf<VibeProject?>(null) }
    var projectToInspect by remember { mutableStateOf<VibeProject?>(null) }

    val currentModel = serverModels.find { it.id == settings.selectedModelId } ?: serverModels.firstOrNull()
    val currentSession = sessions.find { it.id == currentSessionId } ?: sessions.first()
    val listState = rememberLazyListState()

    fun updateCurrentSession(updated: ChatSession) {
        sessions = sessions.map { if (it.id == updated.id) updated else it }
        storage.saveSessions(sessions)
    }

    fun saveAndRefreshProjects(updatedList: List<VibeProject>) {
        projects = updatedList.sortedByDescending { it.updatedAt }
        storage.saveProjects(projects)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(315.dp),
                drawerContainerColor = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .systemBarsPadding()
                        .padding(14.dp)
                ) {
                    // Поиск по проектам и чатам
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Поиск в содержимом чата...", style = MaterialTheme.typography.bodyMedium) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        singleLine = true,
                        shape = RoundedCornerShape(28.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                            unfocusedBorderColor = Color.Transparent,
                            focusedBorderColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Сортируем чаты: сначала закреплённые (isPinned), затем по времени
                    val filteredSessions = remember(sessions, searchQuery) {
                        sessions
                            .filter { s ->
                                searchQuery.isBlank() ||
                                        s.title.contains(searchQuery, ignoreCase = true) ||
                                        s.messages.any { it.text.contains(searchQuery, ignoreCase = true) }
                            }
                            .sortedWith(
                                compareByDescending<ChatSession> { it.isPinned }
                                    .thenByDescending { it.updatedAt }
                            )
                    }

                    val filteredProjects = remember(projects, searchQuery) {
                        projects.filter { p ->
                            searchQuery.isBlank() || p.name.contains(searchQuery, ignoreCase = true)
                        }
                    }

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // ========================================================
                        // РАЗДЕЛ 1: ПРОЕКТЫ (НАД ИСТОРИЕЙ ЧАТОВ, В ПАПКЕ Project)
                        // ========================================================
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Outlined.FolderSpecial,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Проекты (Project/)",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                IconButton(
                                    onClick = { showCreateProjectDialog = true },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.CreateNewFolder,
                                        contentDescription = "Создать проект",
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }

                        if (filteredProjects.isEmpty()) {
                            item {
                                Surface(
                                    onClick = { showCreateProjectDialog = true },
                                    shape = RoundedCornerShape(14.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "Нет проектов. Нажми «+» или кнопку «Добавить в проекты» под файлом ИИ.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(12.dp)
                                    )
                                }
                            }
                        } else {
                            items(filteredProjects, key = { "proj_${it.id}" }) { proj ->
                                Surface(
                                    onClick = { projectToInspect = proj },
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.Folder,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(22.dp)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = proj.name,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "Файлов: ${proj.files.size} • Project/${proj.name}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        // Кнопка: ИИ может добавить, исправить или изменить проект
                                        IconButton(
                                            onClick = {
                                                scope.launch { drawerState.close() }
                                                projectForAiAction = proj
                                            },
                                            modifier = Modifier.size(30.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.AutoFixHigh,
                                                contentDescription = "ИИ: изменить или дополнить проект",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                saveAndRefreshProjects(projects.filterNot { it.id == proj.id })
                                            },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = "Удалить из списка",
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        item {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        }

                        // ========================================================
                        // РАЗДЕЛ 2: ИСТОРИЯ ЧАТОВ (С ЗАКРЕПЛЕНИЕМ)
                        // ========================================================
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "История чатов",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                IconButton(
                                    onClick = {
                                        val newChat = createNewSession()
                                        sessions = listOf(newChat) + sessions
                                        currentSessionId = newChat.id
                                        storage.saveSessions(sessions)
                                        scope.launch { drawerState.close() }
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.AddComment, contentDescription = "Новый чат")
                                }
                            }
                        }

                        items(filteredSessions, key = { it.id }) { session ->
                            val selected = session.id == currentSessionId
                            NavigationDrawerItem(
                                label = {
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (session.isPinned) {
                                                Icon(
                                                    Icons.Default.PushPin,
                                                    contentDescription = "Закреплено",
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier
                                                        .size(14.dp)
                                                        .padding(end = 4.dp)
                                                )
                                            }
                                            Text(
                                                text = session.title,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                fontWeight = if (selected || session.isPinned) FontWeight.Bold else FontWeight.Normal
                                            )
                                        }
                                        Text(
                                            text = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(session.updatedAt)),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                selected = selected,
                                onClick = {
                                    currentSessionId = session.id
                                    scope.launch { drawerState.close() }
                                },
                                icon = {
                                    Icon(Icons.Outlined.Terminal, contentDescription = null)
                                },
                                badge = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        // Кнопка закрепления / открепления чата
                                        IconButton(
                                            onClick = {
                                                sessions = sessions.map {
                                                    if (it.id == session.id) it.copy(isPinned = !it.isPinned) else it
                                                }
                                                storage.saveSessions(sessions)
                                            },
                                            modifier = Modifier.size(26.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (session.isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                                                contentDescription = "Закрепить",
                                                tint = if (session.isPinned) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                        if (sessions.size > 1) {
                                            IconButton(
                                                onClick = {
                                                    sessions = sessions.filterNot { it.id == session.id }
                                                    if (currentSessionId == session.id) {
                                                        currentSessionId = sessions.first().id
                                                    }
                                                    storage.saveSessions(sessions)
                                                },
                                                modifier = Modifier.size(26.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.Close,
                                                    contentDescription = "Удалить",
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(16.dp)
                            )
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    Surface(
                        onClick = {
                            scope.launch { drawerState.close() }
                            showSettingsDialog = true
                        },
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = settings.userName.take(1).uppercase(),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = settings.userName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "Настройки и API-ключи",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(Icons.Outlined.Settings, contentDescription = "Настройки")
                        }
                    }
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = {
                            // Сразу убираем клавиатуру при нажатии на кнопку меню
                            focusManager.clearFocus()
                            keyboardController?.hide()
                            scope.launch { drawerState.open() }
                        }) {
                            Icon(Icons.Default.Menu, contentDescription = "Меню чатов")
                        }
                    },
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = currentSession.title,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { showSettingsDialog = true }) {
                            Icon(Icons.Outlined.VpnKey, contentDescription = "API-ключ и настройки")
                        }
                        IconButton(onClick = {
                            val newChat = createNewSession()
                            sessions = listOf(newChat) + sessions
                            currentSessionId = newChat.id
                            storage.saveSessions(sessions)
                        }) {
                            Icon(Icons.Outlined.AddCircleOutline, contentDescription = "Новый чат")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            },
            bottomBar = {
                BottomPromptDock(
                    prompt = inputPrompt,
                    onPromptChange = { inputPrompt = it },
                    currentModel = currentModel,
                    reasoningEnabled = settings.reasoningEnabled,
                    isGenerating = isGenerating,
                    onToggleReasoning = {
                        onUpdateSettings(settings.copy(reasoningEnabled = !settings.reasoningEnabled))
                    },
                    onOpenModelPicker = {
                        if (serverModels.isEmpty()) {
                            showSettingsDialog = true
                        } else {
                            showModelPickerSheet = true
                        }
                    },
                    onSendPrompt = {
                        val cleanPrompt = inputPrompt.trim()
                        if (cleanPrompt.isEmpty() || isGenerating) return@BottomPromptDock

                        val activeModel = currentModel
                        if (activeModel == null || settings.apiKey.isBlank()) {
                            Toast.makeText(
                                context,
                                "Сначала подключи API-ключ и загрузи модели с сервера в настройках!",
                                Toast.LENGTH_LONG
                            ).show()
                            showSettingsDialog = true
                            return@BottomPromptDock
                        }

                        focusManager.clearFocus()
                        keyboardController?.hide()
                        inputPrompt = ""
                        val userMsg = ChatMessage(isUser = true, text = cleanPrompt)
                        val newTitle = if (currentSession.messages.isEmpty()) cleanPrompt.take(28) else currentSession.title
                        val sessionWithUser = currentSession.copy(
                            title = newTitle,
                            messages = currentSession.messages + userMsg,
                            updatedAt = System.currentTimeMillis()
                        )
                        updateCurrentSession(sessionWithUser)

                        isGenerating = true
                        scope.launch {
                            val aiResponse = AiVibeEngine.generateCodeResponse(
                                context = context,
                                history = sessionWithUser.messages,
                                model = activeModel,
                                settings = settings
                            )
                            val updatedSession = sessionWithUser.copy(
                                messages = sessionWithUser.messages + aiResponse,
                                updatedAt = System.currentTimeMillis()
                            )
                            updateCurrentSession(updatedSession)
                            isGenerating = false
                            if (updatedSession.messages.isNotEmpty()) {
                                listState.animateScrollToItem(updatedSession.messages.lastIndex)
                            }
                        }
                    }
                )
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (currentSession.messages.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        ReCodeLogo(fontSizeSp = 44, showFullText = false)
                        Spacer(modifier = Modifier.height(18.dp))
                        Text(
                            text = "С возвращением,\nначинайте в любое время.",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        if (currentModel != null && settings.apiKey.isNotBlank()) {
                            Text(
                                text = "Активна модель: ${currentModel.displayName} • Доступно моделей: ${serverModels.size}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        } else {
                            FilledTonalButton(
                                onClick = { showSettingsDialog = true },
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Подключить API-ключ и загрузить модели")
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val suggestions = listOf(
                                "Создай неоновый калькулятор на HTML/JS",
                                "Напиши игру Змейка в одном файле",
                                "Сверстай лендинг портфолио с анимациями"
                            )
                            items(suggestions) { suggestion ->
                                AssistChip(
                                    onClick = { inputPrompt = suggestion },
                                    label = { Text(suggestion) },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.AutoAwesome,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    },
                                    shape = RoundedCornerShape(16.dp)
                                )
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(currentSession.messages, key = { it.id }) { msg ->
                            ChatMessageBubble(
                                message = msg,
                                onPreviewHtml = { file -> previewHtmlFile = file },
                                onAddToProjects = { file -> fileToAddToProject = file }
                            )
                        }
                        if (isGenerating) {
                            item {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(8.dp)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = "${currentModel?.displayName ?: "ИИ"} выдает ответ...",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ДИАЛОГ 1: ДОБАВЛЕНИЕ ФАЙЛА В ПРОЕКТЫ (ПО КНОПКЕ ПОД ОВАЛОМ ФАЙЛА)
    fileToAddToProject?.let { targetFile ->
        AddFileToProjectDialog(
            file = targetFile,
            existingProjects = projects,
            defaultProjectName = currentSession.title.take(20).ifBlank { "MyProject" },
            onConfirm = { projectName ->
                scope.launch {
                    val savedInProjectFolder = if (settings.workspaceUri.isNotEmpty()) {
                        withContext(Dispatchers.IO) {
                            AiVibeEngine.writeToProjectDirectory(
                                context = context,
                                treeUri = Uri.parse(settings.workspaceUri),
                                projectName = projectName,
                                relativePath = targetFile.path,
                                content = targetFile.content
                            )
                        }
                    } else false

                    val updatedFile = targetFile.copy(savedToDisk = savedInProjectFolder)
                    val existing = projects.find { it.name.equals(projectName, ignoreCase = true) }
                    val newList = if (existing != null) {
                        val mergedFiles = existing.files.filterNot { it.path == updatedFile.path } + updatedFile
                        projects.map {
                            if (it.id == existing.id) it.copy(files = mergedFiles, updatedAt = System.currentTimeMillis())
                            else it
                        }
                    } else {
                        listOf(
                            VibeProject(
                                name = projectName,
                                files = listOf(updatedFile),
                                updatedAt = System.currentTimeMillis()
                            )
                        ) + projects
                    }
                    saveAndRefreshProjects(newList)
                    fileToAddToProject = null
                    Toast.makeText(
                        context,
                        "Добавлено в проект «$projectName» (Project/$projectName/${targetFile.path})",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            },
            onDismiss = { fileToAddToProject = null }
        )
    }

    // ДИАЛОГ 2: СОЗДАНИЕ НОВОГО ПРОЕКТА ВРУЧНУЮ В МЕНЮ
    if (showCreateProjectDialog) {
        CreateProjectDialog(
            onCreate = { name, fileName, initialCode ->
                scope.launch {
                    val initialFiles = mutableListOf<GeneratedFile>()
                    if (settings.workspaceUri.isNotEmpty()) {
                        withContext(Dispatchers.IO) {
                            AiVibeEngine.ensureNamedProjectFolder(context, Uri.parse(settings.workspaceUri), name)
                            if (fileName.isNotBlank()) {
                                val ok = AiVibeEngine.writeToProjectDirectory(
                                    context = context,
                                    treeUri = Uri.parse(settings.workspaceUri),
                                    projectName = name,
                                    relativePath = fileName,
                                    content = initialCode
                                )
                                initialFiles.add(GeneratedFile(path = fileName, content = initialCode, savedToDisk = ok))
                            }
                        }
                    } else if (fileName.isNotBlank()) {
                        initialFiles.add(GeneratedFile(path = fileName, content = initialCode, savedToDisk = false))
                    }
                    val newProj = VibeProject(name = name, files = initialFiles)
                    saveAndRefreshProjects(listOf(newProj) + projects)
                    showCreateProjectDialog = false
                    Toast.makeText(context, "Проект «$name» создан в папке Project/$name", Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { showCreateProjectDialog = false }
        )
    }

    // ДИАЛОГ 3: ИИ ДОБАВЛЯЕТ, ИСПРАВЛЯЕТ ИЛИ ИЗМЕНЯЕТ ВЫБРАННЫЙ ПРОЕКТ
    projectForAiAction?.let { targetProj ->
        AiModifyProjectDialog(
            project = targetProj,
            onSendTaskToAi = { instruction ->
                projectForAiAction = null
                val activeModel = currentModel
                if (activeModel == null || settings.apiKey.isBlank()) {
                    Toast.makeText(context, "Сначала подключи API-ключ в настройках!", Toast.LENGTH_LONG).show()
                    showSettingsDialog = true
                    return@AiModifyProjectDialog
                }

                val userMsg = ChatMessage(
                    isUser = true,
                    text = "🛠 **Проект «${targetProj.name}»**: $instruction"
                )
                val sessionWithUser = currentSession.copy(
                    title = "Проект: ${targetProj.name}",
                    messages = currentSession.messages + userMsg,
                    updatedAt = System.currentTimeMillis()
                )
                updateCurrentSession(sessionWithUser)

                isGenerating = true
                scope.launch {
                    val aiResponse = AiVibeEngine.generateCodeResponse(
                        context = context,
                        history = sessionWithUser.messages,
                        model = activeModel,
                        settings = settings,
                        targetProject = targetProj
                    )
                    // Автоматически обновляем файлы внутри самого проекта в меню и в папке Project/<name>
                    if (aiResponse.files.isNotEmpty()) {
                        val mergedMap = targetProj.files.associateBy { it.path }.toMutableMap()
                        aiResponse.files.forEach { newF -> mergedMap[newF.path] = newF }
                        val updatedProj = targetProj.copy(
                            files = mergedMap.values.toList(),
                            updatedAt = System.currentTimeMillis()
                        )
                        saveAndRefreshProjects(projects.map { if (it.id == updatedProj.id) updatedProj else it })
                    }

                    val updatedSession = sessionWithUser.copy(
                        messages = sessionWithUser.messages + aiResponse,
                        updatedAt = System.currentTimeMillis()
                    )
                    updateCurrentSession(updatedSession)
                    isGenerating = false
                    if (updatedSession.messages.isNotEmpty()) {
                        listState.animateScrollToItem(updatedSession.messages.lastIndex)
                    }
                }
            },
            onDismiss = { projectForAiAction = null }
        )
    }

    // ДИАЛОГ 4: ПРОСМОТР ФАЙЛОВ ПРОЕКТА И БЫСТРЫЙ ЗАПУСК
    projectToInspect?.let { proj ->
        InspectProjectDialog(
            project = proj,
            onPreviewHtml = { htmlFile ->
                projectToInspect = null
                previewHtmlFile = htmlFile
            },
            onAskAiToModify = {
                projectToInspect = null
                projectForAiAction = proj
            },
            onDismiss = { projectToInspect = null }
        )
    }

    if (showModelPickerSheet) {
        ModelPickerBottomSheet(
            models = serverModels,
            selectedModelId = currentModel?.id.orEmpty(),
            onSelectModel = { model ->
                onUpdateSettings(settings.copy(selectedModelId = model.id))
                showModelPickerSheet = false
            },
            onOpenKeySettings = {
                showModelPickerSheet = false
                showSettingsDialog = true
            },
            onDismiss = { showModelPickerSheet = false }
        )
    }

    if (showSettingsDialog) {
        SettingsDialog(
            settings = settings,
            loadedModelsCount = serverModels.size,
            onUpdateSettings = onUpdateSettings,
            onModelsFetched = { fetchedModels ->
                onUpdateServerModels(fetchedModels)
            },
            onDismiss = { showSettingsDialog = false }
        )
    }

    previewHtmlFile?.let { file ->
        HtmlPreviewDialog(
            file = file,
            onDismiss = { previewHtmlFile = null }
        )
    }
}

// ============================================================================
// ДИАЛОГИ УПРАВЛЕНИЯ ПРОЕКТАМИ (Project/)
// ============================================================================

@Composable
fun AddFileToProjectDialog(
    file: GeneratedFile,
    existingProjects: List<VibeProject>,
    defaultProjectName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var projectName by remember { mutableStateOf(defaultProjectName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Добавить в проекты", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Файл «${file.path}» будет сохранён в папку Project/<Имя_проекта>/ и появится в боковом меню.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = projectName,
                    onValueChange = { projectName = it },
                    label = { Text("Название проекта") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                if (existingProjects.isNotEmpty()) {
                    Text(
                        text = "Или выбери существующий проект:",
                        style = MaterialTheme.typography.labelMedium
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(existingProjects) { proj ->
                            AssistChip(
                                onClick = { projectName = proj.name },
                                label = { Text(proj.name) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val clean = projectName.trim().ifBlank { "MyProject" }
                    onConfirm(clean)
                }
            ) {
                Text("Сохранить в Project")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}

@Composable
fun CreateProjectDialog(
    onCreate: (projectName: String, fileName: String, initialCode: String) -> Unit,
    onDismiss: () -> Unit
) {
    var projectName by remember { mutableStateOf("") }
    var fileName by remember { mutableStateOf("index.html") }
    var initialCode by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новый проект в Project/", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = projectName,
                    onValueChange = { projectName = it },
                    label = { Text("Название проекта") },
                    placeholder = { Text("Например: CoffeeShop") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    label = { Text("Имя стартового файла (опционально)") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = initialCode,
                    onValueChange = { initialCode = it },
                    label = { Text("Исходный код (или оставь пустым для ИИ)") },
                    maxLines = 5,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cleanName = projectName.trim()
                    if (cleanName.isNotEmpty()) {
                        onCreate(cleanName, fileName.trim(), initialCode)
                    }
                },
                enabled = projectName.isNotBlank()
            ) {
                Text("Создать")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}

@Composable
fun AiModifyProjectDialog(
    project: VibeProject,
    onSendTaskToAi: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var taskText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ИИ-ассистент проекта «${project.name}»", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Опиши, что ИИ должен добавить, исправить или изменить в файлах проекта (${project.files.size} шт.). Обновлённый код автоматически сохранится в Project/${project.name}/.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = taskText,
                    onValueChange = { taskText = it },
                    label = { Text("Задача для ИИ") },
                    placeholder = { Text("Например: Добавь тёмную тему, исправь адаптив и сделай анимацию кнопок...") },
                    minLines = 3,
                    maxLines = 6,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSendTaskToAi(taskText.trim()) },
                enabled = taskText.isNotBlank()
            ) {
                Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Выполнить с ИИ")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}

@Composable
fun InspectProjectDialog(
    project: VibeProject,
    onPreviewHtml: (GeneratedFile) -> Unit,
    onAskAiToModify: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Проект: ${project.name}", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Путь: Project/${project.name}/",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                if (project.files.isEmpty()) {
                    Text(
                        text = "В проекте пока нет файлов. Нажми «Изменить с ИИ», чтобы нейросеть сгенерировала код!",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    project.files.forEach { f ->
                        OutlinedCard(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(f.path, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                if (f.path.endsWith(".html", ignoreCase = true)) {
                                    FilledTonalButton(
                                        onClick = { onPreviewHtml(f) },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                                    ) {
                                        Text("Запуск")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onAskAiToModify) {
                Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("ИИ: добавить / изменить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Закрыть")
            }
        }
    )
}

// ============================================================================
// ПОИСК И ФИЛЬТРАЦИЯ МОДЕЛЕЙ В НИЖНЕМ ОКНЕ
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerBottomSheet(
    models: List<AiModelInfo>,
    selectedModelId: String,
    onSelectModel: (AiModelInfo) -> Unit,
    onOpenKeySettings: () -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(ModelFilterCategory.ALL) }

    val filteredModels = remember(models, searchQuery, selectedCategory) {
        models.filter { m ->
            val matchesQuery = searchQuery.isBlank() ||
                    m.displayName.contains(searchQuery, ignoreCase = true) ||
                    m.id.contains(searchQuery, ignoreCase = true)

            val lowerId = m.id.lowercase()
            val matchesCategory = when (selectedCategory) {
                ModelFilterCategory.ALL -> true
                ModelFilterCategory.CODER_PRO -> {
                    lowerId.contains("code") || lowerId.contains("deepseek") ||
                            lowerId.contains("qwen") || lowerId.contains("claude") ||
                            lowerId.contains("gemini") || lowerId.contains("gpt") ||
                            lowerId.contains("pro") || lowerId.contains("sonnet")
                }
                ModelFilterCategory.REASONING -> {
                    lowerId.contains("r1") || lowerId.contains("reason") ||
                            lowerId.contains("think") || lowerId.contains("qwq") ||
                            lowerId.contains("o1") || lowerId.contains("o3") ||
                            lowerId.contains("argon") || lowerId.contains("carbon")
                }
                ModelFilterCategory.FREE -> m.isFree || lowerId.contains(":free") || lowerId.contains("free")
            }
            matchesQuery && matchesCategory
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Модели сервера (${filteredModels.size} из ${models.size})",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Все ИИ в этом списке загружены по твоему API-ключу.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onOpenKeySettings) {
                    Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Ключ / Обновить")
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Поиск (gemini, deepseek, qwen, gpt, free...)") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Очистить")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ModelFilterCategory.entries) { category ->
                    FilterChip(
                        selected = selectedCategory == category,
                        onClick = { selectedCategory = category },
                        label = { Text(category.title) },
                        shape = RoundedCornerShape(14.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(filteredModels, key = { it.id }) { model ->
                    val isSelected = model.id == selectedModelId
                    OutlinedCard(
                        onClick = { onSelectModel(model) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.outlinedCardColors(
                            containerColor = if (isSelected)
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                            else MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = model.displayName,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Surface(
                                        color = if (model.isFree) Color(0xFF00E676).copy(alpha = 0.18f)
                                        else MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            text = model.badge,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (model.isFree) Color(0xFF00E676)
                                            else MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = model.id,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                                )
                                if (model.description.isNotBlank()) {
                                    Text(
                                        text = model.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            RadioButton(
                                selected = isSelected,
                                onClick = { onSelectModel(model) }
                            )
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// НИЖНЯЯ ДОК-КАПСУЛА (ФИКС ДЛИННЫХ НАЗВАНИЙ МОДЕЛЕЙ И РАССУЖДЕНИЯ)
// ============================================================================

@Composable
fun BottomPromptDock(
    prompt: String,
    onPromptChange: (String) -> Unit,
    currentModel: AiModelInfo?,
    reasoningEnabled: Boolean,
    isGenerating: Boolean,
    onToggleReasoning: () -> Unit,
    onOpenModelPicker: () -> Unit,
    onSendPrompt: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
        tonalElevation = 6.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            TextField(
                value = prompt,
                onValueChange = onPromptChange,
                placeholder = {
                    Text(
                        "Спросите...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                maxLines = 4,
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Сокращаем название модели до 17 символов + "...", чтобы окно не растолстело
                    val shortModelTitle = remember(currentModel?.displayName) {
                        currentModel?.displayName?.let { formatShortModelName(it, 17) } ?: "Ключ API"
                    }

                    FilterChip(
                        selected = currentModel != null,
                        onClick = onOpenModelPicker,
                        label = {
                            Text(
                                text = shortModelTitle,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        leadingIcon = {
                            Icon(
                                if (currentModel != null) Icons.Default.Memory else Icons.Default.Key,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        trailingIcon = {
                            Icon(
                                Icons.Default.KeyboardArrowDown,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.widthIn(max = 178.dp)
                    )

                    FilterChip(
                        selected = reasoningEnabled,
                        onClick = onToggleReasoning,
                        label = {
                            Text(
                                text = "Рассуждение",
                                maxLines = 1,
                                softWrap = false
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Psychology,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        shape = RoundedCornerShape(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                FilledIconButton(
                    onClick = onSendPrompt,
                    enabled = prompt.isNotBlank() && !isGenerating,
                    modifier = Modifier.size(40.dp),
                    shape = CircleShape
                ) {
                    Icon(
                        imageVector = if (isGenerating) Icons.Default.HourglassTop else Icons.Default.ArrowUpward,
                        contentDescription = "Отправить"
                    )
                }
            }
        }
    }
}

// ============================================================================
// ОТОБРАЖЕНИЕ СООБЩЕНИЙ (С MARKDOWN) И КНОПКА "ДОБАВИТЬ В ПРОЕКТЫ" ПОД ФАЙЛОМ
// ============================================================================

@Composable
fun ChatMessageBubble(
    message: ChatMessage,
    onPreviewHtml: (GeneratedFile) -> Unit,
    onAddToProjects: (GeneratedFile) -> Unit
) {
    var showReasoning by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (message.isUser) Alignment.End else Alignment.Start
    ) {
        if (!message.isUser && message.modelName.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 4.dp, start = 4.dp)
            ) {
                ReCodeLogo(fontSizeSp = 14)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = message.modelName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        if (!message.isUser && message.reasoningText.isNotEmpty()) {
            Surface(
                onClick = { showReasoning = !showReasoning },
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Psychology,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Цепочка рассуждений",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    AnimatedVisibility(visible = showReasoning) {
                        MarkdownText(
                            markdown = message.reasoningText,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
        }

        Surface(
            shape = RoundedCornerShape(
                topStart = 20.dp,
                topEnd = 20.dp,
                bottomStart = if (message.isUser) 20.dp else 6.dp,
                bottomEnd = if (message.isUser) 6.dp else 20.dp
            ),
            color = if (message.isUser)
                MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.widthIn(max = 345.dp)
        ) {
            // Рендерим полный Markdown (жирный **текст**, заголовки ###, списки и код)
            MarkdownText(
                markdown = message.text,
                modifier = Modifier.padding(14.dp)
            )
        }

        if (message.files.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            message.files.forEach { file ->
                var expandCode by remember { mutableStateOf(false) }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    // Овал карточки файла
                    OutlinedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(22.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        Icons.Default.Code,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = file.path,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = if (file.savedToDisk) "Сохранено в твою папку проекта ✓"
                                            else "Создано в памяти чата",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (file.savedToDisk) Color(0xFF00E676)
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (file.path.endsWith(".html", ignoreCase = true)) {
                                        FilledTonalButton(
                                            onClick = { onPreviewHtml(file) },
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.PlayArrow,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Запуск")
                                        }
                                        Spacer(modifier = Modifier.width(4.dp))
                                    }
                                    IconButton(onClick = { expandCode = !expandCode }) {
                                        Icon(
                                            if (expandCode) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                            contentDescription = "Показать код"
                                        )
                                    }
                                }
                            }

                            AnimatedVisibility(visible = expandCode) {
                                Surface(
                                    color = Color(0xFF0B0C0E),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp)
                                ) {
                                    Text(
                                        text = file.content,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        color = Color(0xFFE0E6ED),
                                        modifier = Modifier.padding(12.dp)
                                    )
                                }
                            }
                        }
                    }

                    // КНОПКА "ДОБАВИТЬ В ПРОЕКТЫ" ПРЯМО ПОД ОВАЛОМ ФАЙЛА (КАК НА 4 ФОТО)
                    Spacer(modifier = Modifier.height(6.dp))
                    FilledTonalButton(
                        onClick = { onAddToProjects(file) },
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Default.CreateNewFolder,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Добавить в проекты",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

// ============================================================================
// 4. ОКНО НАСТРОЕК (БЕЗ ВХОДА GOOGLE, С ЛОКАЛЬНЫМ ПРОФИЛЕМ И ПАПКОЙ Project)
// ============================================================================

@Composable
fun SettingsDialog(
    settings: AppSettings,
    loadedModelsCount: Int,
    onUpdateSettings: (AppSettings) -> Unit,
    onModelsFetched: (List<AiModelInfo>) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isSyncingModels by remember { mutableStateOf(false) }
    var syncStatusMessage by remember {
        mutableStateOf(
            if (loadedModelsCount > 0 && settings.apiKey.isNotBlank())
                "✓ Ключ сохранён. Загружено моделей с сервера: $loadedModelsCount"
            else
                "Вставь API-ключ и нажми «Проверить ключ и загрузить модели»."
        )
    }
    var syncSuccess by remember { mutableStateOf(loadedModelsCount > 0 && settings.apiKey.isNotBlank()) }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
            // В первую очередь создаём папку Project в выбранной директории
            scope.launch(Dispatchers.IO) {
                AiVibeEngine.ensureProjectRootFolder(context, uri)
            }
            onUpdateSettings(settings.copy(workspaceUri = uri.toString()))
            Toast.makeText(context, "Папка Project создана в выбранной директории!", Toast.LENGTH_SHORT).show()
        }
    }

    val effectiveProvider = resolveProvider(settings.preferredProvider, settings.apiKey)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Настройки re:code",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    ReCodeLogo(fontSizeSp = 22)
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    // 1. Подключение API-ключа и запрос списка моделей у сервера
                    SettingsSectionCard(title = "API-ключ и синхронизация моделей") {
                        Text(
                            text = "Выбери провайдера (или оставь Авто: AQ... -> Gemini, sk-5fff5... -> DeepSeek, sk-or-... -> OpenRouter):",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(ApiProviderType.entries) { provider ->
                                FilterChip(
                                    selected = settings.preferredProvider == provider,
                                    onClick = { onUpdateSettings(settings.copy(preferredProvider = provider)) },
                                    label = { Text(provider.displayName) }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedTextField(
                            value = settings.apiKey,
                            onValueChange = { newKey ->
                                onUpdateSettings(settings.copy(apiKey = newKey.trim()))
                            },
                            label = { Text("API-ключ (${effectiveProvider.displayName})") },
                            placeholder = { Text(effectiveProvider.hintPrefix) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            trailingIcon = {
                                if (settings.apiKey.isNotEmpty()) {
                                    IconButton(onClick = {
                                        onUpdateSettings(settings.copy(apiKey = "", selectedModelId = ""))
                                        onModelsFetched(emptyList())
                                        syncSuccess = false
                                        syncStatusMessage = "Ключ удалён, список моделей очищен."
                                    }) {
                                        Icon(Icons.Default.Clear, contentDescription = "Очистить ключ")
                                    }
                                }
                            }
                        )

                        if (effectiveProvider == ApiProviderType.OPENAI) {
                            Spacer(modifier = Modifier.height(10.dp))
                            OutlinedTextField(
                                value = settings.customBaseUrl,
                                onValueChange = { onUpdateSettings(settings.copy(customBaseUrl = it.trim())) },
                                label = { Text("Base URL сервера (например, https://api.openai.com/v1)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = {
                                val currentKey = settings.apiKey.trim()
                                if (!isKeyFormatPlausible(currentKey)) {
                                    syncSuccess = false
                                    syncStatusMessage = "Ошибка: введённый текст не является API-ключом (слишком короткий или неверный формат)."
                                    return@Button
                                }
                                isSyncingModels = true
                                syncStatusMessage = "Запрашиваем список моделей у сервера ${effectiveProvider.displayName}..."
                                scope.launch {
                                    val result = AiVibeEngine.fetchModelsFromServer(
                                        apiKey = currentKey,
                                        provider = effectiveProvider,
                                        customBaseUrl = settings.customBaseUrl
                                    )
                                    isSyncingModels = false
                                    result.onSuccess { models ->
                                        syncSuccess = true
                                        syncStatusMessage = "✓ Ключ сохранён! Сервер вернул моделей: ${models.size}"
                                        onModelsFetched(models)
                                        onUpdateSettings(
                                            settings.copy(
                                                apiKey = currentKey,
                                                selectedModelId = models.first().id
                                            )
                                        )
                                    }.onFailure { err ->
                                        syncSuccess = false
                                        syncStatusMessage = "✗ ${err.message ?: "Не удалось загрузить модели"}"
                                        onModelsFetched(emptyList())
                                    }
                                }
                            },
                            enabled = settings.apiKey.isNotBlank() && !isSyncingModels,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            if (isSyncingModels) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Загрузка с сервера...")
                            } else {
                                Icon(Icons.Default.CloudDownload, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Проверить ключ и загрузить модели")
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            color = if (syncSuccess) Color(0xFF00E676).copy(alpha = 0.14f)
                            else MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = syncStatusMessage,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (syncSuccess) Color(0xFF00E676)
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    // 2. Цвет приложения
                    SettingsSectionCard(title = "Внешний вид и цветовая палитра") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Тёмная тема (AMOLED / Dark)")
                            Switch(
                                checked = settings.darkMode,
                                onCheckedChange = { onUpdateSettings(settings.copy(darkMode = it)) }
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Акцентный цвет приложения:",
                            style = MaterialTheme.typography.labelLarge
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        AppColorTheme.entries.forEach { theme ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onUpdateSettings(settings.copy(colorTheme = theme)) }
                                    .padding(vertical = 8.dp, horizontal = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clip(CircleShape)
                                        .background(theme.primaryColor)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = theme.title,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                RadioButton(
                                    selected = settings.colorTheme == theme,
                                    onClick = { onUpdateSettings(settings.copy(colorTheme = theme)) }
                                )
                            }
                        }
                    }

                    // 3. Размер текста и выбор шрифта
                    SettingsSectionCard(title = "Типографика: размер текста и шрифт") {
                        Text(
                            text = "Масштаб текста: ${(settings.fontScale * 100).toInt()}%",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Slider(
                            value = settings.fontScale,
                            onValueChange = { onUpdateSettings(settings.copy(fontScale = it)) },
                            valueRange = 0.85f..1.30f,
                            steps = 8
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Шрифт интерфейса и редактора:",
                            style = MaterialTheme.typography.labelLarge
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(AppFontChoice.entries) { fontChoice ->
                                FilterChip(
                                    selected = settings.fontChoice == fontChoice,
                                    onClick = { onUpdateSettings(settings.copy(fontChoice = fontChoice)) },
                                    label = { Text(fontChoice.title) }
                                )
                            }
                        }
                    }

                    // 4. Папка для сохранения файлов ИИ (с авто-созданием Project/)
                    SettingsSectionCard(title = "Корневая папка и директория Project") {
                        Text(
                            text = "Рабочая папка (внутри автоматически создаётся папка Project):",
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            text = if (settings.workspaceUri.isNotEmpty()) settings.workspaceUri else "Не выбрана",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        FilledTonalButton(
                            onClick = { folderPickerLauncher.launch(null) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Изменить корневую папку")
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Авто-запись кода в файлы")
                                Text(
                                    "Сразу сохранять файлы в папку Project",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = settings.autoWriteFiles,
                                onCheckedChange = { onUpdateSettings(settings.copy(autoWriteFiles = it)) }
                            )
                        }
                    }

                    // 5. Локальный профиль (без Google) и версия приложения
                    SettingsSectionCard(title = "Профиль и О приложении") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                Icons.Default.AccountCircle,
                                contentDescription = null,
                                modifier = Modifier.size(42.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(settings.userName, fontWeight = FontWeight.Bold)
                                Text(
                                    "Локальный профиль устройства",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = {
                                onDismiss()
                                onUpdateSettings(settings.copy(isLoggedIn = false))
                                Toast.makeText(context, "Вы вышли из профиля", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF5252))
                        ) {
                            Icon(Icons.Default.Logout, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Выйти из аккаунта")
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Версия приложения",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "re:code v1.0.0",
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
fun SettingsSectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

// ============================================================================
// ВСТРОЕННЫЙ ПРЕДПРОСМОТР СОЗДАННЫХ HTML/JS ПРОЕКТОВ
// ============================================================================

@Composable
fun HtmlPreviewDialog(
    file: GeneratedFile,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Предпросмотр: ${file.path}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть")
                    }
                }
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            webViewClient = WebViewClient()
                            loadDataWithBaseURL(null, file.content, "text/html", "UTF-8", null)
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
            }
        }
    }
}

// ============================================================================
// 5. ДВИЖОК ЗАГРУЗКИ МОДЕЛЕЙ, КОДОГЕНЕРАЦИИ И РАБОТЫ С ПАПКОЙ Project/
// ============================================================================

object AiVibeEngine {
    private val client = OkHttpClient.Builder()
        .connectTimeout(45, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private const val SYSTEM_PROMPT = """
Ты — автономный ИИ-разработчик в мобильном приложении re:code.
Отвечай на русском языке, используй аккуратную Markdown-разметку (### заголовки, **жирный текст**, списки).
Каждый раз, когда пользователь просит сделать или изменить проект, сайт, игру, калькулятор или приложение, пиши ПОЛНЫЙ рабочий исходный код без сокращений и без заглушек внутри тегов:
<file path="index.html">
<!DOCTYPE html>
<html lang="ru">
...полный рабочий HTML, CSS и JS код...
</html>
</file>
Если нужно несколько файлов, создай несколько блоков <file path="..."></file>.
"""

    // Гарантирует создание папки "Project" в выбранной пользователем корневой папке
    fun ensureProjectRootFolder(context: Context, treeUri: Uri): DocumentFile? {
        return try {
            val rootDir = DocumentFile.fromTreeUri(context, treeUri) ?: return null
            rootDir.findFile("Project") ?: rootDir.createDirectory("Project")
        } catch (_: Exception) {
            null
        }
    }

    fun ensureNamedProjectFolder(context: Context, treeUri: Uri, projectName: String): DocumentFile? {
        val projectRoot = ensureProjectRootFolder(context, treeUri) ?: return null
        return projectRoot.findFile(projectName) ?: projectRoot.createDirectory(projectName)
    }

    fun writeToProjectDirectory(
        context: Context,
        treeUri: Uri,
        projectName: String,
        relativePath: String,
        content: String
    ): Boolean {
        return try {
            val baseDir = if (projectName.isNotBlank()) {
                ensureNamedProjectFolder(context, treeUri, projectName)
            } else {
                ensureProjectRootFolder(context, treeUri)
            } ?: return false

            val parts = relativePath.split("/").filter { it.isNotBlank() }
            var currentDir = baseDir

            for (i in 0 until parts.size - 1) {
                val folderName = parts[i]
                val nextDir = currentDir.findFile(folderName) ?: currentDir.createDirectory(folderName)
                if (nextDir != null) currentDir = nextDir
            }

            val fileName = parts.last()
            val existingFile = currentDir.findFile(fileName)
            val targetFile = existingFile ?: currentDir.createFile("application/octet-stream", fileName)
            if (targetFile != null) {
                context.contentResolver.openOutputStream(targetFile.uri, "wt")?.use { out ->
                    out.write(content.toByteArray(Charsets.UTF_8))
                }
                true
            } else false
        } catch (_: Exception) {
            false
        }
    }

    suspend fun fetchModelsFromServer(
        apiKey: String,
        provider: ApiProviderType,
        customBaseUrl: String
    ): Result<List<AiModelInfo>> = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        try {
            when (provider) {
                ApiProviderType.GOOGLE_GEMINI -> fetchGeminiModels(cleanKey)
                ApiProviderType.DEEPSEEK -> fetchOpenAiStyleModels(
                    modelsUrl = "https://api.deepseek.com/models",
                    apiKey = cleanKey,
                    provider = ApiProviderType.DEEPSEEK,
                    badgeLabel = "DEEPSEEK"
                )
                ApiProviderType.OPENROUTER -> fetchOpenRouterModelsWithAuthCheck(cleanKey)
                else -> {
                    val base = customBaseUrl.trim().removeSuffix("/").removeSuffix("/chat/completions")
                    fetchOpenAiStyleModels(
                        modelsUrl = "$base/models",
                        apiKey = cleanKey,
                        provider = ApiProviderType.OPENAI,
                        badgeLabel = "API"
                    )
                }
            }
        } catch (e: Exception) {
            Result.failure(Exception("Ошибка соединения с сервером: ${e.localizedMessage}"))
        }
    }

    private fun fetchGeminiModels(apiKey: String): Result<List<AiModelInfo>> {
        val req1 = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey")
            .get()
            .build()

        client.newCall(req1).execute().use { res ->
            if (res.isSuccessful) {
                val body = res.body?.string().orEmpty()
                val parsed = parseGoogleNativeModels(body)
                if (parsed.isNotEmpty()) return Result.success(parsed)
            }
        }

        val req2 = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/openai/models")
            .addHeader("Authorization", "Bearer $apiKey")
            .get()
            .build()

        client.newCall(req2).execute().use { res ->
            val body = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                return Result.failure(Exception("Сервер Google отклонил ключ (код ${res.code}). Проверь ключ AQ... / AIza..."))
            }
            val parsed = parseOpenAiModelsList(body, ApiProviderType.GOOGLE_GEMINI, "GEMINI")
            return if (parsed.isNotEmpty()) Result.success(parsed)
            else Result.failure(Exception("Сервер не вернул доступных моделей Gemini."))
        }
    }

    private fun parseGoogleNativeModels(jsonStr: String): List<AiModelInfo> {
        val root = JSONObject(jsonStr)
        val arr = root.optJSONArray("models") ?: return emptyList()
        val result = mutableListOf<AiModelInfo>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val rawName = obj.optString("name", "").removePrefix("models/")
            if (rawName.isBlank()) continue
            val methods = obj.optJSONArray("supportedGenerationMethods")?.toString().orEmpty()
            if (methods.isNotEmpty() && !methods.contains("generateContent")) continue

            val title = obj.optString("displayName", rawName)
            val desc = obj.optString("description", "Модель Google Gemini API")
            result.add(
                AiModelInfo(
                    id = rawName,
                    displayName = title,
                    badge = "GEMINI",
                    provider = ApiProviderType.GOOGLE_GEMINI,
                    description = desc
                )
            )
        }
        return result.reversed()
    }

    private fun fetchOpenRouterModelsWithAuthCheck(apiKey: String): Result<List<AiModelInfo>> {
        val authReq = Request.Builder()
            .url("https://openrouter.ai/api/v1/auth/key")
            .addHeader("Authorization", "Bearer $apiKey")
            .get()
            .build()

        client.newCall(authReq).execute().use { authRes ->
            if (!authRes.isSuccessful) {
                return Result.failure(Exception("Неверный ключ OpenRouter (код ${authRes.code})."))
            }
        }

        return fetchOpenAiStyleModels(
            modelsUrl = "https://openrouter.ai/api/v1/models",
            apiKey = apiKey,
            provider = ApiProviderType.OPENROUTER,
            badgeLabel = "OR"
        )
    }

    private fun fetchOpenAiStyleModels(
        modelsUrl: String,
        apiKey: String,
        provider: ApiProviderType,
        badgeLabel: String
    ): Result<List<AiModelInfo>> {
        val req = Request.Builder()
            .url(modelsUrl)
            .addHeader("Authorization", "Bearer $apiKey")
            .get()
            .build()

        client.newCall(req).execute().use { res ->
            val body = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                return Result.failure(Exception("Сервер вернул ошибку ${res.code}. Проверь правильность ключа."))
            }
            val models = parseOpenAiModelsList(body, provider, badgeLabel)
            return if (models.isNotEmpty()) {
                Result.success(models)
            } else {
                Result.failure(Exception("Список моделей с сервера пуст."))
            }
        }
    }

    private fun parseOpenAiModelsList(
        jsonStr: String,
        provider: ApiProviderType,
        badgeLabel: String
    ): List<AiModelInfo> {
        val root = JSONObject(jsonStr)
        val arr = root.optJSONArray("data") ?: return emptyList()
        val list = mutableListOf<AiModelInfo>()

        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val id = obj.optString("id", "").removePrefix("models/")
            if (id.isBlank()) continue

            val name = obj.optString("name", id)
            val desc = obj.optString("description", "Загружено с сервера ${provider.displayName}")
            val pricingPrompt = obj.optJSONObject("pricing")?.optString("prompt", "1") ?: "1"
            val isFree = id.contains(":free", ignoreCase = true) || pricingPrompt == "0"

            list.add(
                AiModelInfo(
                    id = id,
                    displayName = name,
                    badge = if (isFree) "FREE" else badgeLabel,
                    provider = provider,
                    description = desc,
                    isFree = isFree
                )
            )
        }
        return list
    }

    suspend fun generateCodeResponse(
        context: Context,
        history: List<ChatMessage>,
        model: AiModelInfo,
        settings: AppSettings,
        targetProject: VibeProject? = null
    ): ChatMessage = withContext(Dispatchers.IO) {
        val key = settings.apiKey.trim()

        // Если ИИ работает над конкретным проектом из меню — передаём текущие файлы проекта в контекст
        val systemPromptWithProject = if (targetProject != null && targetProject.files.isNotEmpty()) {
            buildString {
                append(SYSTEM_PROMPT)
                append("\n\nТекущие файлы проекта «${targetProject.name}»:\n")
                targetProject.files.forEach { f ->
                    append("<file path=\"${f.path}\">\n${f.content}\n</file>\n")
                }
                append("\nВнеси запрошенные пользователем изменения/дополнения и верни обновлённые файлы целиком в тегах <file path=\"...\"></file>.")
            }
        } else {
            SYSTEM_PROMPT
        }

        val apiResult = when (model.provider) {
            ApiProviderType.GOOGLE_GEMINI -> callGeminiChat(key, model.id, history, settings, systemPromptWithProject)
            ApiProviderType.DEEPSEEK -> callStandardOpenAiChat(
                url = "https://api.deepseek.com/chat/completions",
                apiKey = key,
                modelId = model.id,
                history = history,
                settings = settings,
                sysPrompt = systemPromptWithProject
            )
            ApiProviderType.OPENROUTER -> callStandardOpenAiChat(
                url = "https://openrouter.ai/api/v1/chat/completions",
                apiKey = key,
                modelId = model.id,
                history = history,
                settings = settings,
                sysPrompt = systemPromptWithProject
            )
            else -> {
                val base = settings.customBaseUrl.trim().removeSuffix("/").removeSuffix("/chat/completions")
                callStandardOpenAiChat(
                    url = "$base/chat/completions",
                    apiKey = key,
                    modelId = model.id,
                    history = history,
                    settings = settings,
                    sysPrompt = systemPromptWithProject
                )
            }
        }

        val rawResponse = apiResult.first
        val reasoning = apiResult.second

        val parsedFiles = parseAndWriteFiles(
            context = context,
            rawText = rawResponse,
            workspaceUri = settings.workspaceUri,
            autoWrite = settings.autoWriteFiles,
            projectName = targetProject?.name.orEmpty()
        )
        val cleanText = rawResponse.replace(Regex("<file path=\"([^\"]+)\">[\\s\\S]*?</file>"), "").trim()

        ChatMessage(
            isUser = false,
            text = if (cleanText.isNotEmpty()) cleanText else "Готово! Файлы проекта созданы и сохранены в папку Project.",
            reasoningText = reasoning,
            modelName = model.displayName,
            files = parsedFiles
        )
    }

    private fun callGeminiChat(
        apiKey: String,
        modelId: String,
        history: List<ChatMessage>,
        settings: AppSettings,
        sysPrompt: String
    ): Pair<String, String> {
        try {
            val contentsArr = JSONArray()
            history.takeLast(6).forEach { msg ->
                val role = if (msg.isUser) "user" else "model"
                val parts = JSONArray().put(JSONObject().put("text", msg.text))
                contentsArr.put(JSONObject().put("role", role).put("parts", parts))
            }
            val sysInstruction = JSONObject().put(
                "parts",
                JSONArray().put(JSONObject().put("text", sysPrompt))
            )
            val payload = JSONObject()
                .put("systemInstruction", sysInstruction)
                .put("contents", contentsArr)

            val req = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$modelId:generateContent?key=$apiKey")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(req).execute().use { res ->
                if (res.isSuccessful) {
                    val bodyStr = res.body?.string().orEmpty()
                    val json = JSONObject(bodyStr)
                    val parts = json.getJSONArray("candidates")
                        .getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")
                    val sb = StringBuilder()
                    for (i in 0 until parts.length()) {
                        sb.append(parts.getJSONObject(i).optString("text", ""))
                    }
                    return Pair(sb.toString(), "")
                }
            }
        } catch (_: Exception) {
        }

        return callStandardOpenAiChat(
            url = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
            apiKey = apiKey,
            modelId = modelId,
            history = history,
            settings = settings,
            sysPrompt = sysPrompt
        )
    }

    private fun callStandardOpenAiChat(
        url: String,
        apiKey: String,
        modelId: String,
        history: List<ChatMessage>,
        settings: AppSettings,
        sysPrompt: String
    ): Pair<String, String> {
        return try {
            val messagesArray = JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", sysPrompt))
                history.takeLast(6).forEach { msg ->
                    put(
                        JSONObject()
                            .put("role", if (msg.isUser) "user" else "assistant")
                            .put("content", msg.text)
                    )
                }
            }

            val bodyJson = JSONObject()
                .put("model", modelId)
                .put("messages", messagesArray)

            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("HTTP-Referer", "https://recode.app")
                .addHeader("X-Title", "re:code")
                .addHeader("Content-Type", "application/json")
                .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return Pair("Ошибка сервера (${response.code}): $bodyStr", "")
                }
                val json = JSONObject(bodyStr)
                val messageObj = json.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                val content = messageObj.optString("content", "")
                val reasoning = if (settings.reasoningEnabled) {
                    messageObj.optString("reasoning_content", messageObj.optString("reasoning", ""))
                } else ""
                Pair(content, reasoning)
            }
        } catch (e: Exception) {
            Pair("Ошибка сети при обращении к API: ${e.localizedMessage}", "")
        }
    }

    private fun parseAndWriteFiles(
        context: Context,
        rawText: String,
        workspaceUri: String,
        autoWrite: Boolean,
        projectName: String
    ): List<GeneratedFile> {
        val regex = Regex("<file path=\"([^\"]+)\">([\\s\\S]*?)</file>")
        val results = mutableListOf<GeneratedFile>()

        regex.findAll(rawText).forEach { match ->
            val filePath = match.groupValues[1].trim()
            val fileContent = match.groupValues[2].trim()
                .removePrefix("```html")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            var saved = false

            // Всегда сохраняем внутрь папки Project/ (либо Project/<projectName>/)
            if (autoWrite && workspaceUri.isNotEmpty()) {
                saved = writeToProjectDirectory(
                    context = context,
                    treeUri = Uri.parse(workspaceUri),
                    projectName = projectName,
                    relativePath = filePath,
                    content = fileContent
                )
            }
            results.add(GeneratedFile(path = filePath, content = fileContent, savedToDisk = saved))
        }
        return results
    }
}

// ============================================================================
// 6. ЛОКАЛЬНОЕ ХРАНИЛИЩЕ ЧАТОВ (С ЗАКРЕПЛЕНИЕМ), ПРОЕКТОВ И НАСТРОЕК
// ============================================================================

fun createNewSession(): ChatSession = ChatSession(title = "Новый чат")

class AppStorage(private val context: Context) {
    private val prefs = context.getSharedPreferences("recode_prefs", Context.MODE_PRIVATE)
    private val sessionsFile = File(context.filesDir, "recode_sessions.json")
    private val projectsFile = File(context.filesDir, "recode_projects.json")
    private val modelsFile = File(context.filesDir, "recode_server_models.json")

    fun loadSettings(): AppSettings {
        val themeName = prefs.getString("colorTheme", AppColorTheme.DYNAMIC.name) ?: AppColorTheme.DYNAMIC.name
        val fontName = prefs.getString("fontChoice", AppFontChoice.SYSTEM.name) ?: AppFontChoice.SYSTEM.name
        val providerName = prefs.getString("preferredProvider", ApiProviderType.AUTO.name) ?: ApiProviderType.AUTO.name
        val rawUrl = prefs.getString("customBaseUrl", "[https://api.openai.com/v1](https://api.openai.com/v1)") ?: "[https://api.openai.com/v1](https://api.openai.com/v1)"
        val cleanUrl = if (rawUrl.startsWith("[")) "[https://api.openai.com/v1](https://api.openai.com/v1)" else rawUrl

        return AppSettings(
            onboardingCompleted = prefs.getBoolean("onboardingCompleted", false),
            workspaceUri = prefs.getString("workspaceUri", "") ?: "",
            selectedModelId = prefs.getString("selectedModelId", "") ?: "",
            colorTheme = runCatching { AppColorTheme.valueOf(themeName) }.getOrDefault(AppColorTheme.DYNAMIC),
            darkMode = prefs.getBoolean("darkMode", true),
            fontScale = prefs.getFloat("fontScale", 1.0f),
            fontChoice = runCatching { AppFontChoice.valueOf(fontName) }.getOrDefault(AppFontChoice.SYSTEM),
            reasoningEnabled = prefs.getBoolean("reasoningEnabled", true),
            autoWriteFiles = prefs.getBoolean("autoWriteFiles", true),
            apiKey = prefs.getString("apiKey", "") ?: "",
            preferredProvider = runCatching { ApiProviderType.valueOf(providerName) }.getOrDefault(ApiProviderType.AUTO),
            customBaseUrl = cleanUrl,
            userName = prefs.getString("userName", "Разработчик") ?: "Разработчик",
            isLoggedIn = prefs.getBoolean("isLoggedIn", false)
        )
    }

    fun saveSettings(s: AppSettings) {
        prefs.edit()
            .putBoolean("onboardingCompleted", s.onboardingCompleted)
            .putString("workspaceUri", s.workspaceUri)
            .putString("selectedModelId", s.selectedModelId)
            .putString("colorTheme", s.colorTheme.name)
            .putBoolean("darkMode", s.darkMode)
            .putFloat("fontScale", s.fontScale)
            .putString("fontChoice", s.fontChoice.name)
            .putBoolean("reasoningEnabled", s.reasoningEnabled)
            .putBoolean("autoWriteFiles", s.autoWriteFiles)
            .putString("apiKey", s.apiKey)
            .putString("preferredProvider", s.preferredProvider.name)
            .putString("customBaseUrl", s.customBaseUrl)
            .putString("userName", s.userName)
            .putBoolean("isLoggedIn", s.isLoggedIn)
            .commit()
    }

    fun loadServerModels(): List<AiModelInfo> {
        if (!modelsFile.exists()) return emptyList()
        return try {
            val arr = JSONArray(modelsFile.readText())
            val list = mutableListOf<AiModelInfo>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val prov = runCatching { ApiProviderType.valueOf(o.getString("provider")) }
                    .getOrDefault(ApiProviderType.OPENAI)
                list.add(
                    AiModelInfo(
                        id = o.getString("id"),
                        displayName = o.getString("displayName"),
                        badge = o.getString("badge"),
                        provider = prov,
                        description = o.optString("description", ""),
                        isFree = o.optBoolean("isFree", false)
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveServerModels(models: List<AiModelInfo>) {
        try {
            val arr = JSONArray()
            models.forEach { m ->
                arr.put(
                    JSONObject()
                        .put("id", m.id)
                        .put("displayName", m.displayName)
                        .put("badge", m.badge)
                        .put("provider", m.provider.name)
                        .put("description", m.description)
                        .put("isFree", m.isFree)
                )
            }
            modelsFile.writeText(arr.toString())
        } catch (_: Exception) {
        }
    }

    fun loadProjects(): List<VibeProject> {
        if (!projectsFile.exists()) return emptyList()
        return try {
            val arr = JSONArray(projectsFile.readText())
            val list = mutableListOf<VibeProject>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val filesArr = obj.optJSONArray("files") ?: JSONArray()
                val files = mutableListOf<GeneratedFile>()
                for (k in 0 until filesArr.length()) {
                    val f = filesArr.getJSONObject(k)
                    files.add(
                        GeneratedFile(
                            path = f.getString("path"),
                            content = f.getString("content"),
                            savedToDisk = f.optBoolean("savedToDisk", false)
                        )
                    )
                }
                list.add(
                    VibeProject(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        files = files,
                        updatedAt = obj.optLong("updatedAt", System.currentTimeMillis())
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveProjects(projects: List<VibeProject>) {
        try {
            val arr = JSONArray()
            projects.forEach { p ->
                val filesArr = JSONArray()
                p.files.forEach { f ->
                    filesArr.put(
                        JSONObject()
                            .put("path", f.path)
                            .put("content", f.content)
                            .put("savedToDisk", f.savedToDisk)
                    )
                }
                arr.put(
                    JSONObject()
                        .put("id", p.id)
                        .put("name", p.name)
                        .put("updatedAt", p.updatedAt)
                        .put("files", filesArr)
                )
            }
            projectsFile.writeText(arr.toString())
        } catch (_: Exception) {
        }
    }

    fun loadSessions(): List<ChatSession> {
        if (!sessionsFile.exists()) return listOf(createNewSession())
        return try {
            val arr = JSONArray(sessionsFile.readText())
            val list = mutableListOf<ChatSession>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val rawTitle = obj.getString("title")
                val normalizedTitle = if (rawTitle == "Новый вайб-проект") "Новый чат" else rawTitle
                val msgsArr = obj.getJSONArray("messages")
                val msgs = mutableListOf<ChatMessage>()
                for (j in 0 until msgsArr.length()) {
                    val m = msgsArr.getJSONObject(j)
                    val filesArr = m.optJSONArray("files") ?: JSONArray()
                    val files = mutableListOf<GeneratedFile>()
                    for (k in 0 until filesArr.length()) {
                        val f = filesArr.getJSONObject(k)
                        files.add(
                            GeneratedFile(
                                path = f.getString("path"),
                                content = f.getString("content"),
                                savedToDisk = f.optBoolean("savedToDisk", false)
                            )
                        )
                    }
                    msgs.add(
                        ChatMessage(
                            id = m.getString("id"),
                            isUser = m.getBoolean("isUser"),
                            text = m.getString("text"),
                            reasoningText = m.optString("reasoningText", ""),
                            modelName = m.optString("modelName", ""),
                            files = files,
                            timestamp = m.optLong("timestamp", System.currentTimeMillis())
                        )
                    )
                }
                list.add(
                    ChatSession(
                        id = obj.getString("id"),
                        title = normalizedTitle,
                        messages = msgs,
                        isPinned = obj.optBoolean("isPinned", false),
                        updatedAt = obj.optLong("updatedAt", System.currentTimeMillis())
                    )
                )
            }
            if (list.isEmpty()) listOf(createNewSession()) else list
        } catch (e: Exception) {
            listOf(createNewSession())
        }
    }

    fun saveSessions(sessions: List<ChatSession>) {
        try {
            val arr = JSONArray()
            sessions.forEach { s ->
                val msgsArr = JSONArray()
                s.messages.forEach { m ->
                    val filesArr = JSONArray()
                    m.files.forEach { f ->
                        filesArr.put(
                            JSONObject()
                                .put("path", f.path)
                                .put("content", f.content)
                                .put("savedToDisk", f.savedToDisk)
                        )
                    }
                    msgsArr.put(
                        JSONObject()
                            .put("id", m.id)
                            .put("isUser", m.isUser)
                            .put("text", m.text)
                            .put("reasoningText", m.reasoningText)
                            .put("modelName", m.modelName)
                            .put("files", filesArr)
                            .put("timestamp", m.timestamp)
                    )
                }
                arr.put(
                    JSONObject()
                        .put("id", s.id)
                        .put("title", s.title)
                        .put("isPinned", s.isPinned)
                        .put("updatedAt", s.updatedAt)
                        .put("messages", msgsArr)
                )
            }
            sessionsFile.writeText(arr.toString())
        } catch (_: Exception) {
        }
    }
}