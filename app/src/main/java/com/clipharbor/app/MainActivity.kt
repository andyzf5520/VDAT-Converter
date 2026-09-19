package com.clipharbor.app

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.lifecycle.lifecycleScope
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private enum class SourceMode(val label: String) {
    ChunkDirectory("VDAT 视频目录"),
    VdatFile(".vdat 视频文件")
}

private enum class CompletionAction(val label: String) {
    ShowButtons("显示操作按钮"),
    AutoOpenFolder("自动打开目录"),
    None("不自动操作")
}

private enum class FileNameRule(val label: String) {
    SourceName("使用来源名称"),
    CleanVdatName("自动去掉 VDAT 后缀")
}

private enum class ThemeSetting(val label: String) {
    System("跟随系统"),
    Light("浅色"),
    Dark("深色")
}

class MainActivity : ComponentActivity() {
    private var inputTree: Uri? = null
    private var inputFile: Uri? = null
    private var outputTree: Uri? = null
    private var inputName by mutableStateOf("")
    private var sourceMode by mutableStateOf(SourceMode.ChunkDirectory)
    private var selectionRevision by mutableStateOf(0)
    private var outputName by mutableStateOf("默认：Download/VdatConverter")
    private var lastOutputUri by mutableStateOf<Uri?>(null)
    private var lastOutputFolderUri by mutableStateOf<Uri?>(null)
    private var completionAction by mutableStateOf(CompletionAction.ShowButtons)
    private var fileNameRule by mutableStateOf(FileNameRule.CleanVdatName)
    private var themeSetting by mutableStateOf(ThemeSetting.System)
    private var debugLogEnabled by mutableStateOf(false)
    private val settings by lazy { getSharedPreferences("vdat_converter_settings", MODE_PRIVATE) }

    private val chooseInput = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@registerForActivityResult
        persistReadWrite(uri)
        val resolved = runCatching { resolveVideoTree(uri) }.getOrNull()
        inputTree = resolved?.first ?: uri
        inputFile = null
        sourceMode = SourceMode.ChunkDirectory
        inputName = (resolved?.second ?: runCatching { queryName(uri) }.getOrNull() ?: uri.toString())
            .removeSuffix(".vdat_contents")
        lastOutputUri = null
        lastOutputFolderUri = null
        selectionRevision++
    }
    private val chooseVdatFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        persistReadWrite(uri)
        inputTree = null
        inputFile = uri
        sourceMode = SourceMode.VdatFile
        inputName = (runCatching { queryName(uri) }.getOrNull() ?: uri.toString()).removeSuffix(".vdat")
        lastOutputUri = null
        lastOutputFolderUri = null
        selectionRevision++
    }
    private val chooseOutput = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@registerForActivityResult
        outputTree = uri
        persistReadWrite(uri)
        outputName = "保存到：" + (runCatching { queryName(uri) }.getOrNull() ?: uri.toString())
        saveOutputTree(uri, outputName)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadSettings()
        setContent {
            ConverterScreen(
                inputName = inputName,
                outputName = outputName,
                sourceMode = sourceMode,
                completionAction = completionAction,
                fileNameRule = fileNameRule,
                themeSetting = themeSetting,
                debugLogEnabled = debugLogEnabled,
                selectionRevision = selectionRevision,
                onChooseInput = { chooseInput.launch(null) },
                onChooseVdatFile = { chooseVdatFile.launch(arrayOf("*/*")) },
                onChooseOutput = { chooseOutput.launch(null) },
                onModeChanged = { sourceMode = it },
                onCompletionActionChanged = ::updateCompletionAction,
                onFileNameRuleChanged = ::updateFileNameRule,
                onThemeSettingChanged = ::updateThemeSetting,
                onDebugLogChanged = ::updateDebugLogEnabled,
                canOpenOutput = lastOutputUri != null && completionAction == CompletionAction.ShowButtons,
                canOpenOutputFolder = lastOutputFolderUri != null && completionAction == CompletionAction.ShowButtons,
                onOpenOutput = ::openLastOutput,
                onOpenOutputFolder = ::openLastOutputFolder,
                onOpenGithub = ::openGithub,
                onConvert = ::convertRequested
            )
        }
    }

    private fun loadSettings() {
        completionAction = enumValueOrDefault(settings.getString("completion_action", null), CompletionAction.ShowButtons)
        fileNameRule = enumValueOrDefault(settings.getString("file_name_rule", null), FileNameRule.CleanVdatName)
        themeSetting = enumValueOrDefault(settings.getString("theme", null), ThemeSetting.System)
        debugLogEnabled = settings.getBoolean("debug_log", false)
        settings.getString("output_tree", null)?.let { saved ->
            outputTree = Uri.parse(saved)
            outputName = settings.getString("output_name", outputName) ?: outputName
        }
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(name: String?, default: T): T =
        runCatching { if (name == null) default else enumValueOf<T>(name) }.getOrDefault(default)

    private fun saveOutputTree(uri: Uri, label: String) {
        settings.edit().putString("output_tree", uri.toString()).putString("output_name", label).apply()
    }

    private fun updateCompletionAction(value: CompletionAction) {
        completionAction = value
        settings.edit().putString("completion_action", value.name).apply()
    }

    private fun updateFileNameRule(value: FileNameRule) {
        fileNameRule = value
        settings.edit().putString("file_name_rule", value.name).apply()
    }

    private fun updateThemeSetting(value: ThemeSetting) {
        themeSetting = value
        settings.edit().putString("theme", value.name).apply()
    }

    private fun updateDebugLogEnabled(value: Boolean) {
        debugLogEnabled = value
        settings.edit().putBoolean("debug_log", value).apply()
    }

    private fun persistReadWrite(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }

    private fun convertRequested(customName: String, report: (String) -> Unit) {
        val file = inputFile
        val tree = inputTree
        if (file == null && tree == null) { report("转换失败：请先选择视频目录或 .vdat 文件"); return }
        lastOutputUri = null
        lastOutputFolderUri = null
        lifecycleScope.launch {
            runCatching {
                if (file != null) convertVdatFile(file, customName, report)
                else convertTree(tree!!, customName, report)
            }
                .onSuccess {
                    lastOutputUri = it.uri
                    lastOutputFolderUri = it.folderUri
                    report("转换完成：${it.displayName}")
                    if (completionAction == CompletionAction.AutoOpenFolder) openLastOutputFolder()
                }
                .onFailure { report("转换失败：${it.message ?: "未知错误"}") }
        }
    }

    private fun openLastOutput() {
        val uri = lastOutputUri ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(Intent.createChooser(intent, "查看转换后的视频")) }
            .onFailure { Toast.makeText(this, "没有找到可打开 MP4 的应用", Toast.LENGTH_SHORT).show() }
    }

    private fun openLastOutputFolder() {
        val uri = lastOutputFolderUri ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "vnd.android.document/directory")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }
            .onFailure { Toast.makeText(this, "当前系统文件管理器不支持直接打开目录", Toast.LENGTH_SHORT).show() }
    }

    private fun openGithub() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/andyzf5520/VDAT-Converter"))
        runCatching { startActivity(intent) }
            .onFailure { Toast.makeText(this, "无法打开 GitHub 地址", Toast.LENGTH_SHORT).show() }
    }

    private suspend fun convertVdatFile(input: Uri, customName: String, report: (String) -> Unit): OutputFile = withContext(Dispatchers.IO) {
        val sourceName = queryName(input) ?: "vdat_video.vdat"
        val safeName = makeOutputName(customName, sourceName)
        val probe = contentResolver.openInputStream(input)?.use { stream ->
            val bytes = ByteArray(12)
            val count = stream.read(bytes)
            count == 12 && String(bytes, 4, 4, Charsets.US_ASCII) == "ftyp"
        } ?: error("无法读取 .vdat 文件")
        require(probe) { "这个 .vdat 是播放列表/元数据，请选择同名的 .vdat_contents 分片目录" }
        val destination = createDestination(safeName)
        try {
            report("正在复制 .vdat 视频…")
            contentResolver.openInputStream(input)?.use { source ->
                contentResolver.openOutputStream(destination, "w")?.use { target -> source.copyTo(target, 256 * 1024) }
                    ?: error("无法打开输出文件")
            } ?: error("无法读取 .vdat 文件")
            if (outputTree == null) contentResolver.update(destination, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            OutputFile(safeName, destination, currentOutputFolderUri())
        } catch (e: Exception) {
            contentResolver.delete(destination, null, null)
            throw e
        }
    }

    private suspend fun convertTree(input: Uri, customName: String, report: (String) -> Unit): OutputFile = withContext(Dispatchers.IO) {
        val children = listChildren(input)
        val key = children.firstOrNull { it.name == "0.key" } ?: error("目录中没有 0.key")
        val chunks = children.mapNotNull { child -> child.name.toIntOrNull()?.let { it to child } }.sortedBy { it.first }
        require(chunks.isNotEmpty()) { "目录中没有数字视频分片" }
        val aesKey = contentResolver.openInputStream(key.uri)?.use { it.readBytes() } ?: error("无法读取 0.key")
        require(aesKey.size == 16) { "0.key 不是有效的 AES-128 密钥" }
        val safeName = makeOutputName(customName, queryName(input) ?: "vdat_video")
        val destination = createDestination(safeName)
        val tempOutput = File.createTempFile("vdat-output-", ".mp4", cacheDir)
        try {
            convertChunksWithFfmpeg(chunks, aesKey, tempOutput, report)
            tempOutput.inputStream().use { input ->
                contentResolver.openOutputStream(destination, "w")?.use { output -> input.copyTo(output, 256 * 1024) }
                    ?: error("无法打开输出文件")
            }
            if (outputTree == null) {
                contentResolver.update(destination, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
            OutputFile(safeName, destination, currentOutputFolderUri())
        } catch (e: Exception) {
            contentResolver.delete(destination, null, null)
            throw e
        } finally {
            tempOutput.delete()
        }
    }

    private fun convertChunksWithFfmpeg(chunks: List<Pair<Int, Child>>, key: ByteArray, output: File, report: (String) -> Unit) {
        val combined = File.createTempFile("vdat-combined-", ".ts", cacheDir)
        try {
            chunks.forEachIndexed { index, pair ->
                report("正在解密并合并分片 ${index + 1}/${chunks.size}…")
                decryptToFile(pair.second.uri, key, combined, append = true)
            }
            report("正在封装完整 MP4…")
            val logLevel = if (debugLogEnabled) "info" else "error"
            val command = "-y -hide_banner -loglevel $logLevel -i ${shellQuote(combined.absolutePath)} -map 0 -c copy -movflags +faststart ${shellQuote(output.absolutePath)}"
            val session = FFmpegKit.execute(command)
            val detail = if (debugLogEnabled) session.allLogsAsString else session.failStackTrace
            require(ReturnCode.isSuccess(session.returnCode)) { "FFmpeg 封装失败：${detail ?: session.state}" }
            require(output.isFile && output.length() > 0) { "FFmpeg 没有生成有效 MP4" }
        } finally {
            combined.delete()
        }
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun createDestination(name: String): Uri {
        outputTree?.let { tree ->
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            return DocumentsContract.createDocument(contentResolver, parent, "video/mp4", name)
                ?: error("无法在选择的目录创建 MP4")
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/VdatConverter")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("无法创建下载文件")
    }

    private fun currentOutputFolderUri(): Uri {
        outputTree?.let { return it }
        return DocumentsContract.buildTreeDocumentUri(
            "com.android.externalstorage.documents",
            "primary:${Environment.DIRECTORY_DOWNLOADS}/VdatConverter"
        )
    }

    private fun muxChunksToMp4(chunks: List<Pair<Int, Child>>, key: ByteArray, descriptor: java.io.FileDescriptor, report: (String) -> Unit) {
        val muxer = MediaMuxer(descriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        val combined = File.createTempFile("vdat-combined-", ".ts", cacheDir)
        try {
            chunks.forEachIndexed { index, pair ->
                report("正在解密并合并分片 ${index + 1}/${chunks.size}…")
                decryptToFile(pair.second.uri, key, combined, append = true)
            }

            report("正在封装完整 MP4…")
            val extractor = MediaExtractor().apply { setDataSource(combined.absolutePath) }
            try {
                require(extractor.trackCount > 0) { "合并后的媒体无法识别" }
                val trackMap = (0 until extractor.trackCount).associateWith { muxer.addTrack(extractor.getTrackFormat(it)) }
                val lastPts = LongArray(extractor.trackCount) { Long.MIN_VALUE }
                val offsets = LongArray(extractor.trackCount)
                muxer.start()
                started = true
                for (track in 0 until extractor.trackCount) extractor.selectTrack(track)
                val buffer = ByteBuffer.allocate(4 * 1024 * 1024)
                val info = MediaCodec.BufferInfo()
                while (true) {
                    val track = extractor.sampleTrackIndex
                    if (track < 0) break
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    var pts = extractor.sampleTime
                    if (lastPts[track] != Long.MIN_VALUE && pts + offsets[track] <= lastPts[track]) {
                        offsets[track] += lastPts[track] - (pts + offsets[track]) + 1
                    }
                    pts += offsets[track]
                    lastPts[track] = pts
                    info.set(0, size, pts, extractor.sampleFlags)
                    muxer.writeSampleData(trackMap.getValue(track), buffer, info)
                    extractor.advance()
                }
            } finally { extractor.release() }
            require(started) { "没有可封装的媒体轨道" }
            muxer.stop()
        } finally {
            muxer.release()
            combined.delete()
        }
    }

    private fun decryptToFile(uri: Uri, key: ByteArray, destination: File, append: Boolean) {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ByteArray(16)))
        contentResolver.openInputStream(uri)?.use { input ->
            CipherOutputStream(FileOutputStream(destination, append), cipher).use { output -> input.copyTo(output, 256 * 1024) }
        } ?: error("无法读取分片")
    }

    private fun makeOutputName(custom: String, source: String): String {
        val base = custom.trim().ifBlank { source.removeSuffix(".vdat_contents") }
        val clean = base.replace(Regex("[\\\\/:*?\"<>|]"), "_").removeSuffix(".mp4")
        require(clean.isNotBlank()) { "输出文件名不能为空" }
        return "$clean.mp4"
    }

    private fun queryName(uri: Uri): String? {
        val documentUri = if (DocumentsContract.isTreeUri(uri)) DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri)) else uri
        return contentResolver.query(documentUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }

    private fun listChildren(tree: Uri): List<Child> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val result = mutableListOf<Child>()
        contentResolver.query(childrenUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                result += Child(cursor.getString(1), DocumentsContract.buildDocumentUriUsingTree(tree, id))
            }
        }
        return result
    }

    private fun resolveVideoTree(selected: Uri): Pair<Uri, String>? {
        if (isVideoTree(selected)) return selected to (queryName(selected) ?: "vdat_video")
        val children = listChildren(selected)
        val candidates = children.filter { it.name.endsWith(".vdat_contents") && isVideoTree(it.uri) }
        if (candidates.isEmpty()) return null
        val vdatNames = children.map { it.name }.toSet()
        val matched = candidates.firstOrNull { (it.name.removeSuffix(".vdat_contents") + ".vdat") in vdatNames }
        val chosen = matched ?: candidates.singleOrNull() ?: error("目录中有多个视频，请选择具体的 .vdat_contents 目录")
        return chosen.uri to chosen.name
    }

    private fun isVideoTree(tree: Uri): Boolean {
        val children = listChildren(tree)
        return children.any { it.name == "0.key" } && children.any { it.name.toIntOrNull() != null }
    }

    private data class Child(val name: String, val uri: Uri)
    private data class OutputFile(val displayName: String, val uri: Uri, val folderUri: Uri)
}

@Composable
private fun ConverterScreen(
    inputName: String,
    outputName: String,
    sourceMode: SourceMode,
    completionAction: CompletionAction,
    fileNameRule: FileNameRule,
    themeSetting: ThemeSetting,
    debugLogEnabled: Boolean,
    selectionRevision: Int,
    onChooseInput: () -> Unit,
    onChooseVdatFile: () -> Unit,
    onChooseOutput: () -> Unit,
    onModeChanged: (SourceMode) -> Unit,
    onCompletionActionChanged: (CompletionAction) -> Unit,
    onFileNameRuleChanged: (FileNameRule) -> Unit,
    onThemeSettingChanged: (ThemeSetting) -> Unit,
    onDebugLogChanged: (Boolean) -> Unit,
    canOpenOutput: Boolean,
    canOpenOutputFolder: Boolean,
    onOpenOutput: () -> Unit,
    onOpenOutputFolder: () -> Unit,
    onOpenGithub: () -> Unit,
    onConvert: (String, (String) -> Unit) -> Unit
) {
    var customName by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("请选择 VDAT 视频目录或 .vdat 文件") }
    var running by remember { mutableStateOf(false) }
    var overflowExpanded by remember { mutableStateOf(false) }
    var settingsVisible by remember { mutableStateOf(false) }
    var aboutVisible by remember { mutableStateOf(false) }

    LaunchedEffect(inputName, selectionRevision, fileNameRule) {
        customName = defaultOutputName(inputName, fileNameRule)
    }

    val useDarkTheme = when (themeSetting) {
        ThemeSetting.System -> isSystemInDarkTheme()
        ThemeSetting.Light -> false
        ThemeSetting.Dark -> true
    }
    val colorScheme = if (useDarkTheme) {
        darkColorScheme(
            primary = Color(0xFFBBA6FF),
            onPrimary = Color(0xFF24134F),
            secondary = Color(0xFFD0C3E8),
            background = Color(0xFF171222),
            surface = Color(0xFF211A2F)
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF6950B8),
            onPrimary = Color.White,
            secondary = Color(0xFF625B71),
            background = Color(0xFFF9F7FF),
            surface = Color.White
        )
    }
    val background = if (useDarkTheme) {
        Brush.verticalGradient(listOf(Color(0xFF191326), Color(0xFF211A2F), Color(0xFF120F18)))
    } else {
        Brush.verticalGradient(listOf(Color(0xFFEDE7FF), Color(0xFFF9F7FF), Color.White))
    }
    val headerColor = if (useDarkTheme) Color(0xFF3A2B65) else Color(0xFF6B50B8)

    MaterialTheme(
        colorScheme = colorScheme
    ) {
        if (settingsVisible) {
            SettingsDialog(
                outputName = outputName,
                completionAction = completionAction,
                fileNameRule = fileNameRule,
                themeSetting = themeSetting,
                debugLogEnabled = debugLogEnabled,
                onChooseOutput = onChooseOutput,
                onCompletionActionChanged = onCompletionActionChanged,
                onFileNameRuleChanged = onFileNameRuleChanged,
                onThemeSettingChanged = onThemeSettingChanged,
                onDebugLogChanged = onDebugLogChanged,
                onDismiss = { settingsVisible = false }
            )
        }
        if (aboutVisible) {
            AboutDialog(
                onOpenGithub = onOpenGithub,
                onDismiss = { aboutVisible = false }
            )
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(background)
                    .padding(horizontal = 20.dp, vertical = 18.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Spacer(Modifier.height(12.dp))
                Card(
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = headerColor)
                ) {
                    Row(
                        Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("VDAT Converter v1.0.1", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
                            Text("轻松将 VDAT 视频保存为完整 MP4", style = MaterialTheme.typography.bodyMedium, color = Color(0xFFEDE7FF))
                        }
                        Box {
                            IconButton(onClick = { overflowExpanded = true }) {
                                Text("⋮", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                            }
                            DropdownMenu(expanded = overflowExpanded, onDismissRequest = { overflowExpanded = false }) {
                                DropdownMenuItem(text = { Text("设置") }, onClick = { overflowExpanded = false; settingsVisible = true })
                                DropdownMenuItem(text = { Text("关于软件") }, onClick = { overflowExpanded = false; aboutVisible = true })
                                DropdownMenuItem(text = { Text("开源项目地址") }, onClick = { overflowExpanded = false; onOpenGithub() })
                                DropdownMenuItem(text = { Text("版本：v1.0.1") }, enabled = false, onClick = {})
                            }
                        }
                    }
                }
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("转换设置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("步骤 1 · 保存位置", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        OutlinedButton(onClick = onChooseOutput, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) { Text(outputName) }
                        Text("步骤 2 · 视频来源类型", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            SourceMode.values().forEach { mode ->
                                val selected = sourceMode == mode
                                if (selected) {
                                    Button(
                                        onClick = { onModeChanged(mode) },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(14.dp)
                                    ) { Text(mode.label) }
                                } else {
                                    OutlinedButton(
                                        onClick = { onModeChanged(mode) },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(14.dp)
                                    ) { Text(mode.label) }
                                }
                            }
                        }
                        Text("步骤 3 · ${if (sourceMode == SourceMode.ChunkDirectory) "选择视频目录" else "选择 .vdat 文件"}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        OutlinedButton(
                            onClick = if (sourceMode == SourceMode.ChunkDirectory) onChooseInput else onChooseVdatFile,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp)
                        ) { Text(if (inputName.isBlank()) "选择${sourceMode.label}" else inputName) }
                        OutlinedTextField(value = customName, onValueChange = { customName = it }, singleLine = true, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(), label = { Text("输出文件名") }, placeholder = { Text("可修改，自动使用来源名称") })
                    }
                }
                Button(enabled = inputName.isNotBlank() && !running, onClick = {
                    running = true
                    onConvert(customName) { message ->
                        status = message
                        if (message.startsWith("转换完成") || message.startsWith("转换失败")) running = false
                    }
                }, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(18.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6B50B8))) { if (running) CircularProgressIndicator(color = Color.White) else Text("开始转换") }
                if (canOpenOutput || canOpenOutputFolder) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            enabled = canOpenOutput,
                            onClick = onOpenOutput,
                            modifier = Modifier.weight(1f).height(50.dp),
                            shape = RoundedCornerShape(18.dp)
                        ) { Text("查看视频") }
                        OutlinedButton(
                            enabled = canOpenOutputFolder,
                            onClick = onOpenOutputFolder,
                            modifier = Modifier.weight(1f).height(50.dp),
                            shape = RoundedCornerShape(18.dp)
                        ) { Text("打开所在目录") }
                    }
                }
                Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Text(status, Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("支持 VDAT 视频目录和完整 .vdat 视频文件；仅处理你拥有或获授权的视频。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun defaultOutputName(inputName: String, rule: FileNameRule): String {
    val base = when (rule) {
        FileNameRule.SourceName -> inputName
        FileNameRule.CleanVdatName -> inputName.removeSuffix(".vdat_contents").removeSuffix(".vdat")
    }
    return base.ifBlank { "vdat_video" }
}

@Composable
private fun SettingsDialog(
    outputName: String,
    completionAction: CompletionAction,
    fileNameRule: FileNameRule,
    themeSetting: ThemeSetting,
    debugLogEnabled: Boolean,
    onChooseOutput: () -> Unit,
    onCompletionActionChanged: (CompletionAction) -> Unit,
    onFileNameRuleChanged: (FileNameRule) -> Unit,
    onThemeSettingChanged: (ThemeSetting) -> Unit,
    onDebugLogChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        title = { Text("设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingsActionRow("默认保存目录", outputName, onChooseOutput)
                SettingsDropdownRow("转换完成后", completionAction.label, CompletionAction.values().map { it.label }) { index ->
                    onCompletionActionChanged(CompletionAction.values()[index])
                }
                SettingsDropdownRow("文件名规则", fileNameRule.label, FileNameRule.values().map { it.label }) { index ->
                    onFileNameRuleChanged(FileNameRule.values()[index])
                }
                SettingsDropdownRow("主题外观", themeSetting.label, ThemeSetting.values().map { it.label }) { index ->
                    onThemeSettingChanged(ThemeSetting.values()[index])
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("调试日志", fontWeight = FontWeight.SemiBold)
                        Text(if (debugLogEnabled) "开启" else "关闭", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = debugLogEnabled, onCheckedChange = onDebugLogChanged)
                }
            }
        }
    )
}

@Composable
private fun SettingsActionRow(title: String, value: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(value, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SettingsDropdownRow(title: String, value: String, options: List<String>, onSelected: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(value, style = MaterialTheme.typography.bodySmall)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = { expanded = false; onSelected(index) }
                )
            }
        }
    }
}

@Composable
private fun AboutDialog(onOpenGithub: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        dismissButton = { TextButton(onClick = onOpenGithub) { Text("打开 GitHub") } },
        title = { Text("关于软件") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("VDAT Converter", fontWeight = FontWeight.Bold)
                Text("版本：v1.0.1")
                Text("用于将本地 VDAT 视频转换为 MP4，支持 VDAT 视频目录和完整 .vdat 视频文件。")
                Text("开源项目地址：")
                Text("github.com/andyzf5520/VDAT-Converter", color = MaterialTheme.colorScheme.primary)
                Text("请仅转换自己拥有或获授权的视频。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}
