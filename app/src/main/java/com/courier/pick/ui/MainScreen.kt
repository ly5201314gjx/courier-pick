package com.courier.pick

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Bundle
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CenterFocusWeak
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.LocalShipping
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.courier.pick.model.Algorithm
import com.courier.pick.model.AnalysisLog
import com.courier.pick.model.FrameResult
import com.courier.pick.model.ScanEntry
import com.courier.pick.ui.overlay.DetectionOverlay
import com.courier.pick.ui.theme.*
import com.courier.pick.util.ImageLoader
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel) {
    val mode by vm.mode.collectAsStateWithLifecycle()
    val track by vm.track.collectAsStateWithLifecycle()
    val algo by vm.algo.collectAsStateWithLifecycle()
    val liveResult by vm.liveResult.collectAsStateWithLifecycle()
    val staticResult by vm.staticResult.collectAsStateWithLifecycle()
    val staticBitmap by vm.staticBitmap.collectAsStateWithLifecycle()
    val analyzing by vm.analyzing.collectAsStateWithLifecycle()
    val logs by vm.logs.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val hitEvent by vm.hitEvent.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val kbController = LocalSoftwareKeyboardController.current

    var showHistory by remember { mutableStateOf(false) }
    // Hit feedback: pulse a green ring + banner each time a target matches.
    var hitFlash by remember { mutableStateOf(false) }
    val hitScale by animateFloatAsState(
        if (hitFlash) 1.15f else 1f,
        animationSpec = spring(dampingRatio = 0.35f, stiffness = 500f),
        label = "hit"
    )
    LaunchedEffect(hitEvent) {
        if (hitEvent > 0L) {
            vm.vibrate()
            hitFlash = true
            delay(500)
            hitFlash = false
        }
    }

    var cameraGranted by remember { mutableStateOf(false) }
    val cameraPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> cameraGranted = granted }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val bmp = context.contentResolver.openInputStream(uri)?.let { stream ->
                ImageLoader.decode(stream)
            }
            vm.setStaticBitmap(bmp, track)
        }
    }

    // Request camera when entering dynamic mode without permission
    LaunchedEffect(mode) {
        if (mode == ScanMode.DYNAMIC) {
            cameraGranted = ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.CAMERA
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!cameraGranted) {
                cameraPermLauncher.launch(android.Manifest.permission.CAMERA)
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(iOSBackground)
    ) {
        Scaffold(
            containerColor = iOSBackground,
            contentWindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp)
        ) { pad ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Header(title = "极速找快递", subtitle = "单号锁定 · 快速找件")
                    }
                    IconButton(onClick = { showHistory = true }) {
                        Icon(Icons.AutoMirrored.Outlined.List, "历史记录", tint = iOSBlue, modifier = Modifier.size(24.dp))
                    }
                }
                Spacer(Modifier.height(10.dp))

                // Mode segmented control (dynamic / static)
                ModeSegmentedControl(
                    mode = mode,
                    onChange = {
                        vm.setMode(it)
                        kbController?.hide()
                        if (it == ScanMode.STATIC) photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                )
                Spacer(Modifier.height(12.dp))

                // Algorithm switcher
                AlgorithmSelector(algo = algo, onChange = vm::setAlgorithm)
                Spacer(Modifier.height(12.dp))

                // Tracking number input card (paste + favorite)
                TrackInputCard(
                    value = track,
                    isFavorite = track.isNotBlank() && favorites.contains(track.trim()),
                    onChange = vm::setTrackNumber,
                    onPaste = {
                        clipboard.getText()?.let { vm.setTrackNumber(it.text.trim()) }
                    },
                    onClear = { vm.setTrackNumber("") },
                    onToggleFav = if (track.isBlank()) null else {
                        { vm.toggleFavorite(track) }
                    }
                )
                Spacer(Modifier.height(10.dp))

                // Favorite quick-fill chips
                if (favorites.isNotEmpty()) {
                    FavoritesRow(
                        items = favorites,
                        onPick = vm::setTrackNumber,
                        onRemove = vm::toggleFavorite
                    )
                    Spacer(Modifier.height(10.dp))
                }

                // Preview area
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = true)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color.Black)
                ) {
                    when (mode) {
                        ScanMode.DYNAMIC -> {
                            if (cameraGranted) {
                                CameraArea(
                                    vm = vm,
                                    liveResult = liveResult,
                                    algo = algo,
                                    hitFlash = hitFlash,
                                    hitScale = hitScale
                                )
                            } else {
                                Placeholder(
                                    icon = { Icons.Filled.Videocam },
                                    title = "需要相机权限",
                                    subtitle = "允许访问相机以实时扫描快递面的条形码与单号",
                                    action = {
                                        cameraPermLauncher.launch(android.Manifest.permission.CAMERA)
                                    },
                                    actionText = "授权相机"
                                )
                            }
                        }
                        ScanMode.STATIC -> {
                            if (analyzing) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(Icons.Outlined.AutoAwesome, null,
                                            tint = Color.White, modifier = Modifier.size(44.dp))
                                        Spacer(Modifier.height(12.dp))
                                        Text("正在识别图片…", color = Color.White, fontSize = 16.sp)
                                    }
                                }
                            } else if (staticBitmap != null) {
                                StaticImageArea(staticBitmap!!, staticResult, algo, hitFlash, hitScale)
                            } else {
                                Placeholder(
                                    icon = { Icons.Filled.PhotoLibrary },
                                    title = "选择一张含快递码的图片",
                                    subtitle = "选择一个图片静态识别其中的快递单号与条形码",
                                    action = {
                                        photoPicker.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                        )
                                    },
                                    actionText = "选择图片"
                                )
                            }
                        }
                    }
                }

                // Staged processing feedback shown live under the static image
                if (mode == ScanMode.STATIC && (analyzing || logs.isNotEmpty())) {
                    LogsPanel(logs = logs, analyzing = analyzing)
                    Spacer(Modifier.height(10.dp))
                }

                Spacer(Modifier.height(12.dp))
                val result = if (mode == ScanMode.DYNAMIC) liveResult else staticResult
                StatusCard(result = result, track = track, analyzing = analyzing, algo = algo,
                    onReAnalyze = { if (mode == ScanMode.STATIC) vm.reanalyze() },
                    onShare = { vm.shareSummary(result, track) })
                Spacer(Modifier.height(12.dp))
            }
        }

        // Success banner overlay on hit
        AnimatedVisibility(
            visible = hitFlash,
            enter = fadeIn() + scaleIn(initialScale = 0.8f),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.Center)
                .graphicsLayer { scaleX = hitScale; scaleY = hitScale }
        ) {
            SuccessBanner(text = "目标命中  ✓")
        }

        if (showHistory) {
            HistorySheet(
                onDismiss = { showHistory = false },
                history = history,
                onClear = vm::clearHistory,
                onRemove = vm::removeHistory
            )
        }
    }
}

@Composable
private fun SuccessBanner(text: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0xFF1CB63B), RoundedCornerShape(50))
            .shadow(10.dp, RoundedCornerShape(50))
            .padding(horizontal = 22.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CheckCircle, null, tint = Color.White, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Header(title: String, subtitle: String) {
    Column {
        Text(title, fontSize = 32.sp, fontWeight = FontWeight.Bold, color = iOSSystemLabel)
        Spacer(Modifier.height(2.dp))
        Text(
            subtitle,
            fontSize = 14.sp,
            color = iOSTertiaryLabel,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun ModeSegmentedControl(mode: ScanMode, onChange: (ScanMode) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(iOSFill)
            .padding(4.dp)
    ) {
        listOf(ScanMode.DYNAMIC to "实时识别", ScanMode.STATIC to "静态识别").forEach { (m, label) ->
            val selected = mode == m
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(13.dp))
                    .background(if (selected) Color.White else Color.Transparent)
                    .shadow(
                        if (selected) 4.dp else 0.dp,
                        RoundedCornerShape(13.dp),
                        spotColor = Color(0x22000000)
                    )
                    .clickable { onChange(m) },
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (m == ScanMode.DYNAMIC) Icons.Filled.Videocam
                        else Icons.Filled.PhotoLibrary,
                        null,
                        tint = if (selected) iOSBlue else iOSTertiaryLabel,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        label,
                        fontSize = 15.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (selected) iOSSystemLabel else iOSTertiaryLabel
                    )
                }
            }
        }
    }
}

@Composable
private fun AlgorithmSelector(algo: Algorithm, onChange: (Algorithm) -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("识别算法", fontSize = 13.sp, color = iOSTertiaryLabel, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(algo.label, fontSize = 13.sp, color = iOSBlue, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(iOSFill)
                .padding(3.dp)
        ) {
            listOf(
                Algorithm.COURIER to Icons.Outlined.LocalShipping,
                Algorithm.PERSON to Icons.Outlined.Person,
                Algorithm.BARCODE to Icons.Outlined.QrCodeScanner
            ).forEach { (a, icon) ->
                val selected = algo == a
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(11.dp))
                        .background(if (selected) Color.White else Color.Transparent)
                        .shadow(if (selected) 3.dp else 0.dp, RoundedCornerShape(11.dp), spotColor = Color(0x22000000))
                        .clickable { onChange(a) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(icon, null, tint = if (selected) iOSBlue else iOSTertiaryLabel, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            a.label,
                            fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (selected) iOSSystemLabel else iOSTertiaryLabel,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackInputCard(
    value: String,
    isFavorite: Boolean,
    onChange: (String) -> Unit,
    onPaste: () -> Unit,
    onClear: () -> Unit,
    onToggleFav: (() -> Unit)?
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(60.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(iOSSurface)
            .border(1.dp, iOSFill, RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.Search, null, tint = iOSBlue, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            androidx.compose.foundation.text.BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = iOSSystemLabel),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(iOSBlue),
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty())
                            Text("输入或粘贴快递单号（支持多个用逗号分隔）", color = iOSTertiaryLabel, fontSize = 14.sp)
                        inner()
                    }
                }
            )
        }
        if (onToggleFav != null) {
            IconButton(onClick = onToggleFav) {
                Icon(
                    if (isFavorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                    "收藏",
                    tint = if (isFavorite) iOSRed else iOSTertiaryLabel,
                    modifier = Modifier.size(21.dp)
                )
            }
        }
        if (value.isNotEmpty()) {
            IconButton(onClick = onClear) {
                Icon(Icons.Filled.Clear, "清除", tint = iOSTertiaryLabel, modifier = Modifier.size(20.dp))
            }
        }
        TextButton(onClick = onPaste) {
            Icon(Icons.Filled.ContentPaste, null, tint = iOSBlue, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("粘贴", color = iOSBlue, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun FavoritesRow(
    items: List<String>,
    onPick: (String) -> Unit,
    onRemove: (String) -> Boolean
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("收藏:", fontSize = 12.sp, color = iOSTertiaryLabel, fontWeight = FontWeight.SemiBold)
        items.forEach { n ->
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xFFFFEEF0))
                    .border(1.dp, Color(0xFFFFD1D6), RoundedCornerShape(50))
                    .clickable { onPick(n) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(n, fontSize = 12.sp, color = iOSRed, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.Filled.Clear,
                    "取消收藏",
                    tint = Color(0xFFFF9AA4),
                    modifier = Modifier
                        .size(12.dp)
                        .clickable { onRemove(n) }
                )
            }
        }
    }
}

@Composable
private fun CameraArea(
    vm: MainViewModel,
    liveResult: FrameResult,
    algo: Algorithm,
    hitFlash: Boolean,
    hitScale: Float
) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val analyzer = vm.analyzer
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val executor = remember { ContextCompat.getMainExecutor(context) }

    LaunchedEffect(Unit) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val provider = providerFuture.get()
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val analysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(1280, 720))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { it.setAnalyzer(executor, analyzer) }
        provider.unbindAll()
        provider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            analysis
        )
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        DetectionOverlay(liveResult, Modifier.fillMaxSize())
        // live stats (fps / latency) top-left
        LiveStatsBadge(result = liveResult, algo = algo, modifier = Modifier
            .align(Alignment.TopStart)
            .padding(12.dp))
        if (algo == Algorithm.PERSON || liveResult.personCount > 0) {
            PersonCountBadge(
                count = liveResult.personCount,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
            )
        }
        // corner hint
        if (algo == Algorithm.COURIER) {
            Text(
                if (liveResult.matched) "红框即为目标快递" else "对准条形码开始扫描",
                color = Color(0xDDFFFFFF),
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0x66000000))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        } else if (algo == Algorithm.PERSON) {
            Text(
                "黄色框为人头 / 目标",
                color = Color(0xDDFFFFFF),
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0x66000000))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun LiveStatsBadge(result: FrameResult, algo: Algorithm, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xB3000000))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            "${if (result.fps > 0) "${result.fps.roundToInt()} fps" else "-- fps"}  ·  延迟 ${result.latencyMs}ms",
            color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
        )
        Text(
            when (algo) {
                Algorithm.COURIER -> "条码 ${result.codes.size} · 对象 ${result.objects.size}"
                Algorithm.PERSON -> "识别模式：人头计数"
                Algorithm.BARCODE -> "条码聚焦 ${result.codes.size}"
            },
            color = Color(0xFFB9BCC2), fontSize = 11.sp
        )
    }
}

@Composable
private fun PersonCountBadge(count: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0xB3FFB300), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.Person, null, tint = Color.Black, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(5.dp))
        Text("人头 $count", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StaticImageArea(
    bitmap: Bitmap,
    staticResult: FrameResult,
    algo: Algorithm,
    hitFlash: Boolean,
    hitScale: Float
) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        val ar = bitmap.width.toFloat() / bitmap.height
        val wDp = if (maxHeight * ar > maxWidth) maxWidth else maxHeight * ar
        val hDp = wDp / ar
        Box(Modifier.size(wDp, hDp)) {
            androidx.compose.foundation.Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
            DetectionOverlay(staticResult, Modifier.fillMaxSize())
            if (algo == Algorithm.PERSON || staticResult.personCount > 0) {
                PersonCountBadge(
                    count = staticResult.personCount,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                )
            }
        }
    }
}

@Composable
private fun LogsPanel(logs: List<AnalysisLog>, analyzing: Boolean) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFFEAF0FF))
            .border(1.dp, Color(0xFFD3E2FF), RoundedCornerShape(18.dp))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.AutoAwesome, null, tint = iOSBlue, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (analyzing) "正在识别…" else "识别过程", fontSize = 13.sp, color = iOSBlue, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        logs.forEachIndexed { i, log ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(16.dp)
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (log.ok) Color(0xFF34C759) else Color(0xFFFF3B30),
                            RoundedCornerShape(50)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (log.ok) {
                        Text("✓", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    } else {
                        Text("!", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(log.step, fontSize = 13.sp, color = iOSSystemLabel, fontWeight = FontWeight.SemiBold)
                        if (log.elapsedMs > 0) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "${log.elapsedMs}ms",
                                fontSize = 11.sp,
                                color = iOSBlue,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    if (log.detail.isNotBlank()) {
                        Spacer(Modifier.height(1.dp))
                        Text(log.detail, fontSize = 12.sp, color = iOSSecondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Placeholder(
    icon: @Composable () -> androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    action: () -> Unit,
    actionText: String
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color.White, RoundedCornerShape(22.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon(), null, tint = iOSBlue, modifier = Modifier.size(32.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                subtitle,
                color = Color(0xFFB9BCC2),
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = action,
                colors = ButtonDefaults.buttonColors(containerColor = iOSBlue),
                shape = RoundedCornerShape(50)
            ) {
                Text(actionText, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun StatusCard(
    result: FrameResult,
    track: String,
    analyzing: Boolean,
    algo: Algorithm,
    onReAnalyze: () -> Unit,
    onShare: () -> Unit
) {
    val matched = result.matched
    val matchedCount = result.matchedCodes.size
    val codeCount = result.codes.size
    val objCount = result.objects.size
    val personCount = result.personCount

    val bg = if (matched) Color(0xFFE5F6EC) else iOSSurface
    val fg = if (matched) iOSGreen else iOSSystemLabel

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(bg)
            .border(1.dp, if (matched) Color(0xFFB8E6C8) else iOSFill, RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(50))
                .background(if (matched) Color(0xFF34C759).copy(alpha = 0.15f) else iOSBlue.copy(alpha = 0.12f), RoundedCornerShape(50)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (matched) Icons.Filled.CheckCircle else Icons.Outlined.CenterFocusWeak,
                null,
                tint = if (matched) iOSGreen else iOSBlue,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            when {
                matched -> {
                    Text("已找到 ${matchedCount} 个匹配快递", fontWeight = FontWeight.Bold, color = fg)
                    Text(
                        if (algo == Algorithm.PERSON) "识别到 $personCount 个人头目标"
                        else "对应的红框即为您的快递，请前往取件",
                        fontSize = 13.sp,
                        color = iOSSecondaryLabel
                    )
                }
                analyzing && algo == Algorithm.PERSON -> {
                    Text("正在识别中…", fontWeight = FontWeight.SemiBold, color = fg)
                    Text("AI 正在统计人像与人数", fontSize = 13.sp, color = iOSSecondaryLabel)
                }
                analyzing -> {
                    Text("正在识别中…", fontWeight = FontWeight.SemiBold, color = fg)
                    Text("AI 正在解析快递条码 / 单号", fontSize = 13.sp, color = iOSSecondaryLabel)
                }
                algo == Algorithm.PERSON -> {
                    Text("识别人像 $personCount 个", fontWeight = FontWeight.SemiBold, color = fg)
                    Text("黄色框为人头目标，可切换算法寻找快递", fontSize = 13.sp, color = iOSSecondaryLabel)
                }
                result.codes.isNotEmpty() -> {
                    Text("已识别 ${codeCount} 个快递码", fontWeight = FontWeight.SemiBold, color = fg)
                    Text(
                        "未匹配到目标单号" +
                            (if (personCount > 0) " · 人像 $personCount" else "") +
                            (if (objCount > 0) " · AI 检测 $objCount 个对象" else ""),
                        fontSize = 13.sp,
                        color = iOSSecondaryLabel
                    )
                }
                else -> {
                    Text("等待扫描…", fontWeight = FontWeight.SemiBold, color = fg)
                    Text(
                        if (track.isBlank()) "请输入目标快递单号后对准快递面"
                        else "请将相机对准快递条码，正在检测目标快递",
                        fontSize = 13.sp,
                        color = iOSSecondaryLabel
                    )
                }
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            IconButton(onClick = onShare, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Filled.Share, "分享结果", tint = iOSBlue, modifier = Modifier.size(20.dp))
            }
            TextButton(onClick = onReAnalyze, enabled = !analyzing, contentPadding = PaddingValues(0.dp)) {
                Icon(Icons.Outlined.Refresh, null, tint = if (analyzing) iOSTertiaryLabel else iOSBlue, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(3.dp))
                Text("重新识别", color = if (analyzing) iOSTertiaryLabel else iOSBlue, fontSize = 12.sp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistorySheet(
    onDismiss: () -> Unit,
    history: List<ScanEntry>,
    onClear: () -> Unit,
    onRemove: (Long) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = iOSSurface
    ) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("识别历史", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = iOSSystemLabel)
                Spacer(Modifier.weight(1f))
                if (history.isNotEmpty()) {
                    TextButton(onClick = onClear) {
                        Icon(Icons.Outlined.Delete, null, tint = iOSRed, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("清空", color = iOSRed, fontSize = 14.sp)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            if (history.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                    Text("暂无历史记录", color = iOSTertiaryLabel, fontSize = 14.sp)
                }
            } else {
                LazyColumn(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                ) {
                    items(history.size) { i ->
                        val e = history[i]
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(
                                        if (e.matched) Color(0xFFE5F6EC) else Color(0xFFF0F0F2),
                                        RoundedCornerShape(50)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    if (e.matched) Icons.Filled.CheckCircle else Icons.Outlined.AutoAwesome,
                                    null,
                                    tint = if (e.matched) iOSGreen else iOSBlue,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(e.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = iOSSystemLabel)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "${e.detail} · ${fmtTime(e.ts)} · ${e.algorithm}",
                                    fontSize = 12.sp,
                                    color = iOSTertiaryLabel
                                )
                            }
                            IconButton(onClick = { onRemove(e.id) }) {
                                Icon(Icons.Outlined.Delete, "删除", tint = iOSTertiaryLabel, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun fmtTime(ts: Long): String {
    val sdf = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(ts))
}