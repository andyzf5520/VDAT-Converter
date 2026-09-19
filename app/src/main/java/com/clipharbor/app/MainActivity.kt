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
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

class MainActivity : ComponentActivity() {
    private var inputTree: Uri? = null
    private var inputFile: Uri? = null
    private var outputTree: Uri? = null
    private var inputName by mutableStateOf("")
    private var sourceMode by mutableStateOf(SourceMode.ChunkDirectory)
    private var selectionRevision by mutableStateOf(0)
    private var outputName by mutableStateOf("默认：Download/VdatConverter")

    private val chooseInput = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@registerForActivityResult
        persistReadWrite(uri)
        val resolved = runCatching { resolveVideoTree(uri) }.getOrNull()
        inputTree = resolved?.first ?: uri
        inputFile = null
        sourceMode = SourceMode.ChunkDirectory
        inputName = (resolved?.second ?: runCatching { queryName(uri) }.getOrNull() ?: uri.toString())
            .removeSuffix(".vdat_contents")
        selectionRevision++
    }
    private val chooseVdatFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        persistReadWrite(uri)
        inputTree = null
        inputFile = uri
        sourceMode = SourceMode.VdatFile
        inputName = (runCatching { queryName(uri) }.getOrNull() ?: uri.toString()).removeSuffix(".vdat")
        selectionRevision++
    }
    private val chooseOutput = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@registerForActivityResult
        outputTree = uri
        persistReadWrite(uri)
        outputName = "保存到：" + (runCatching { queryName(uri) }.getOrNull() ?: uri.toString())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ConverterScreen(
                inputName = inputName,
                outputName = outputName,
                sourceMode = sourceMode,
                selectionRevision = selectionRevision,
                onChooseInput = { chooseInput.launch(null) },
                onChooseVdatFile = { chooseVdatFile.launch(arrayOf("*/*")) },
                onChooseOutput = { chooseOutput.launch(null) },
                onModeChanged = { sourceMode = it },
                onConvert = ::convertRequested
            )
        }
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
        lifecycleScope.launch {
            runCatching {
                if (file != null) convertVdatFile(file, customName, report)
                else convertTree(tree!!, customName, report)
            }
                .onSuccess { report("转换完成：${it.displayName}") }
                .onFailure { report("转换失败：${it.message ?: "未知错误"}") }
        }
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
            OutputFile(safeName)
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
            OutputFile(safeName)
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
            val command = "-y -hide_banner -loglevel error -i ${shellQuote(combined.absolutePath)} -map 0 -c copy -movflags +faststart ${shellQuote(output.absolutePath)}"
            val session = FFmpegKit.execute(command)
            require(ReturnCode.isSuccess(session.returnCode)) { "FFmpeg 封装失败：${session.failStackTrace ?: session.state}" }
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
    private data class OutputFile(val displayName: String)
}

@Composable
private fun ConverterScreen(
    inputName: String,
    outputName: String,
    sourceMode: SourceMode,
    selectionRevision: Int,
    onChooseInput: () -> Unit,
    onChooseVdatFile: () -> Unit,
    onChooseOutput: () -> Unit,
    onModeChanged: (SourceMode) -> Unit,
    onConvert: (String, (String) -> Unit) -> Unit
) {
    var customName by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("请选择 VDAT 视频目录或 .vdat 文件") }
    var running by remember { mutableStateOf(false) }
    var modeMenuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(inputName, selectionRevision) {
        val defaultName = inputName
            .removeSuffix(".vdat_contents")
            .removeSuffix(".vdat")
            .ifBlank { "vdat_video" }
        // 每次重新选择来源都切换到新来源名称，之后用户仍可继续编辑。
        customName = defaultName
    }

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF6950B8),
            onPrimary = Color.White,
            secondary = Color(0xFF625B71),
            background = Color(0xFFF9F7FF),
            surface = Color.White
        )
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color(0xFFEDE7FF), Color(0xFFF9F7FF), Color.White)))
                    .padding(horizontal = 20.dp, vertical = 18.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Spacer(Modifier.height(12.dp))
                Card(
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF6B50B8))
                ) {
                    Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("VDAT Converter", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color.White)
                        Text("轻松将 VDAT 视频保存为完整 MP4", color = Color(0xFFEDE7FF))
                    }
                }
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("设置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("步骤 1 · 保存位置", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        OutlinedButton(onClick = onChooseOutput, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) { Text(outputName) }
                        Text("步骤 2 · 视频来源", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Box {
                    OutlinedButton(onClick = { modeMenuExpanded = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) { Text("来源类型：${sourceMode.label}") }
                    DropdownMenu(expanded = modeMenuExpanded, onDismissRequest = { modeMenuExpanded = false }) {
                        SourceMode.values().forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(mode.label) },
                                onClick = { modeMenuExpanded = false; onModeChanged(mode) }
                            )
                        }
                    }
                }
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
                Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Text(status, Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("支持 VDAT 视频目录和完整 .vdat 视频文件；仅处理你拥有或获授权的视频。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
