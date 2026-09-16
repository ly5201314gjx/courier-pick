package com.courier.pick

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.courier.pick.analysis.CourierFrameAnalyzer
import com.courier.pick.detection.YoloDetector
import com.courier.pick.detection.ZxingDecoder
import com.courier.pick.model.Algorithm
import com.courier.pick.model.AnalysisLog
import com.courier.pick.model.FrameResult
import com.courier.pick.model.ScanEntry
import com.courier.pick.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ScanMode { DYNAMIC, STATIC }

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs(app)

    val yolo = YoloDetector(app)
    val zxing = ZxingDecoder()

    private val _mode = MutableStateFlow(ScanMode.DYNAMIC)
    val mode: StateFlow<ScanMode> = _mode

    private val _algo = MutableStateFlow(Algorithm.COURIER)
    val algo: StateFlow<Algorithm> = _algo

    private val _track = MutableStateFlow("")
    val track: StateFlow<String> = _track

    private val _staticBitmap = MutableStateFlow<Bitmap?>(null)
    val staticBitmap: StateFlow<Bitmap?> = _staticBitmap

    private val _staticResult = MutableStateFlow(FrameResult())
    val staticResult: StateFlow<FrameResult> = _staticResult

    private val _analyzing = MutableStateFlow(false)
    val analyzing: StateFlow<Boolean> = _analyzing

    /** Staged processing feedback shown under the static image. */
    private val _logs = MutableStateFlow<List<AnalysisLog>>(emptyList())
    val logs: StateFlow<List<AnalysisLog>> = _logs

    private val _history = MutableStateFlow<List<ScanEntry>>(prefs.history())
    val history: StateFlow<List<ScanEntry>> = _history

    private val _favorites = MutableStateFlow<List<String>>(prefs.favorites())
    val favorites: StateFlow<List<String>> = _favorites

    /** Live analyzer shared with CameraX. */
    val analyzer = CourierFrameAnalyzer(
        yolo = yolo,
        zxing = zxing,
        yoloIntervalMs = 200
    )
    val liveResult: StateFlow<FrameResult> = analyzer.result

    /** Non-null the instant a target is hit — UI reacts to this edge to play feedback. */
    private val _hitEvent = MutableStateFlow(0L)
    val hitEvent: StateFlow<Long> = _hitEvent

    init {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { yolo.loadIfNeeded() }
        }
        viewModelScope.launch {
            analyzer.trackFlow.value = _track.value
        }
        analyzer.algorithmFlow.value = _algo.value
    }

    // ---------------- Mode & algorithm ----------------

    fun setMode(m: ScanMode) {
        _mode.value = m
        if (m == ScanMode.STATIC && _staticBitmap.value == null) {
            _analyzing.value = false
            _logs.value = emptyList()
        }
    }

    fun setAlgorithm(a: Algorithm) {
        _algo.value = a
        analyzer.algorithmFlow.value = a
    }

    fun setTrackNumber(s: String) {
        _track.value = s
        analyzer.trackFlow.value = s
    }

    fun toggleFavorite(number: String): Boolean {
        val n = number.trim()
        if (n.isBlank()) return false
        return if (prefs.isFavorite(n)) {
            prefs.removeFavorite(n)
            _favorites.value = prefs.favorites()
            false
        } else {
            prefs.addFavorite(n)
            _favorites.value = prefs.favorites()
            true
        }
    }

    fun historySize(): Int = _history.value.size

    fun clearHistory() {
        prefs.clearHistory()
        _history.value = emptyList()
    }

    fun removeHistory(id: Long) {
        prefs.removeHistory(id)
        _history.value = prefs.history()
    }

    // ---------------- Static pipeline (staged feedback) ----------------

    fun setStaticBitmap(bmp: Bitmap?, track: String) {
        _staticBitmap.value = bmp
        if (bmp == null) {
            _staticResult.value = FrameResult()
            _analyzing.value = false
            _logs.value = emptyList()
            return
        }
        runStatic(bmp, track, redo = false)
    }

    fun reanalyze() {
        val bmp = _staticBitmap.value ?: return
        runStatic(bmp, _track.value, redo = true)
    }

    private fun runStatic(bmp: Bitmap, track: String, redo: Boolean) {
        val algo = _algo.value
        _analyzing.value = true
        _logs.value = emptyList()
        viewModelScope.launch(Dispatchers.Default) {
            val logs = ArrayList<AnalysisLog>()
            val t0 = System.nanoTime()
            fun emit(title: String, detail: String, final: Boolean = false) {
                val ms = if (final) (System.nanoTime() - t0) / 1_000_000L else 0L
                logs.add(AnalysisLog(title, detail, ms, true))
                _logs.value = logs.toList()
            }
            emit("读取图片", "${bmp.width}×${bmp.height}")
            delay(70)

            var codes = emptyList<com.courier.pick.model.DetectedCode>()
            if (algo != Algorithm.PERSON) {
                emit("解析条码 / 单号", "ZXing 多码扫描中…")
                delay(60)
                codes = runCatching { zxing.decodeBitmap(bmp, track) }.getOrDefault(emptyList())
            }
            emit("条码 · 二维码", if (codes.isEmpty()) "未发现" else "发现 ${codes.size} 个码")
            delay(40)

            var objects = emptyList<com.courier.pick.model.DetectedObject>()
            if (algo != Algorithm.BARCODE) {
                emit("AI 目标识别", "YOLO 神经网络推理中…")
                delay(60)
                objects = runCatching { yolo.detect(bmp) }.getOrDefault(emptyList())
                    .let { if (algo == Algorithm.PERSON) it.filter { o -> o.label.contains("person") } else it }
                delay(30)
            }
            emit(
                if (algo == Algorithm.PERSON) "人头 · 人数" else "AI 对象",
                when {
                    algo == Algorithm.PERSON -> "识别 ${objects.size} 个人像"
                    else -> if (objects.isEmpty()) "未发现" else "发现 ${objects.size} 个对象"
                }
            )

            val mapped = codes.map {
                if (it.matched) it else it.copy(matched = it.matched || matchedAny(it.text, track))
            }
            val matchedCount = mapped.count { it.matched }
            emit(
                "单号匹配",
                if (matchedCount > 0) "$matchedCount 个命中目标 ✓" else "未匹配到目标"
            )
            delay(50)

            emit("识别完成", "共 ${mapped.size} 码 · ${objects.size} 对象", final = true)
            _staticResult.value = FrameResult(
                mapped, objects, track,
                bmp.width.toFloat() / bmp.height,
                latencyMs = (System.nanoTime() - t0) / 1_000_000L,
                ts = System.currentTimeMillis()
            )
            _analyzing.value = false

            if (matchedCount > 0 || mapped.isNotEmpty() || algo == Algorithm.PERSON) {
                val title = when {
                    matchedCount > 0 -> "命中 $matchedCount 个单号"
                    algo == Algorithm.PERSON -> "识别 ${objects.size} 个人像"
                    mapped.isNotEmpty() -> "识别 ${mapped.size} 个条码"
                    else -> "扫描"
                }
                addHistory(ScanEntry(
                    id = -1, ts = System.currentTimeMillis(), title = title,
                    detail = if (track.isBlank()) "静态识别" else "目标 $track · 静态",
                    matched = matchedCount > 0, algorithm = algo.name
                ))
            }
            if (matchedCount > 0) _hitEvent.value = System.currentTimeMillis()
        }
    }

    private fun matchedAny(text: String, track: String): Boolean {
        if (track.isBlank()) return false
        val a = text.uppercase().replace(" ", "")
        return track.uppercase().split(Regex("[^A-Z0-9]+"))
            .filter { it.length >= 4 }
            .any { t -> a.contains(t) || t.contains(a) }
    }

    // ---------------- History ----------------

    private fun addHistory(entry: ScanEntry) {
        prefs.addHistory(entry)
        _history.value = prefs.history()
    }

    /** Called by the live UI when a target is matched in the camera feed. */
    fun recordLiveMatch(count: Int, track: String, algo: Algorithm) {
        addHistory(ScanEntry(
            id = -1,
            ts = System.currentTimeMillis(),
            title = "命中 $count 个单号",
            detail = if (track.isBlank()) "实时识别" else "目标 $track",
            matched = true,
            algorithm = algo.name
        ))
        _hitEvent.value = System.currentTimeMillis()
    }

    // ---------------- Feedback (vibrate) ----------------

    fun vibrate() {
        runCatching {
            val context = getApplication<Application>()
            val vib = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (Build.VERSION.SDK_INT >= 26) {
                vib.vibrate(VibrationEffect.createWaveform(
                    longArrayOf(0, 60, 50, 80), -1
                ))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(longArrayOf(0, 60, 50, 80), -1)
            }
        }
    }

    // ---------------- Sharing ----------------

    fun shareSummary(result: FrameResult, track: String) {
        val sb = StringBuilder()
        sb.append("📦 极速找快递 · 识别结果\n")
        sb.append("时间：").append(SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())).append("\n")
        sb.append("目标单号：").append(if (track.isBlank()) "无" else track).append("\n")
        sb.append("算法：").append(_algo.value.label).append("\n")
        sb.append("匹配命中：").append(result.matchedCodes.size).append(" 个\n")
        sb.append("识别条码：").append(result.codes.size).append(" 个\n")
        if (result.personCount > 0) sb.append("人像人数：").append(result.personCount).append("\n")
        if (result.codes.isNotEmpty()) {
            sb.append("\n条码列表：\n")
            result.codes.forEachIndexed { i, it ->
                sb.append("  ").append(i + 1).append(". ")
                    .append(it.text.ifBlank { it.format })
                    .append(if (it.matched) "  ✓命中" else "").append("\n")
            }
        }
        sendShareSnippet(sb.toString())
    }

    private fun sendShareSnippet(text: String) {
        runCatching {
            val context = getApplication<Application>()
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_TEXT, text)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(
                android.content.Intent.createChooser(intent, "分享识别结果")
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            Toast.makeText(getApplication(), "没有可用的分享应用", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCleared() {
        runCatching { yolo.close() }
        super.onCleared()
    }
}