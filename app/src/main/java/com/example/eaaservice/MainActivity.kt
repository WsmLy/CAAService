package com.example.eaaservice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.telephony.SmsManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.eaaservice.data.ContactRepository
import com.example.eaaservice.ui.theme.EAAServiceTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

// 联系人数据类
data class Contact(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val phoneNumber: String,
    val color: Color
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EAAServiceTheme {
                EmergencyContactScreen()
            }
        }
    }
}

@Composable
fun EmergencyContactScreen() {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val textToSpeech = remember {
        var tts: TextToSpeech? = null
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.CHINA
            }
        }
        tts
    }

    DisposableEffect(Unit) {
        onDispose {
            textToSpeech.stop()
            textToSpeech.shutdown()
        }
    }

    // 创建 Repository
    val repository = remember { ContactRepository(context) }

    // 从 DataStore 读取联系人列表
    var childContacts by remember { mutableStateOf(listOf<Contact>()) }

    // 使用 LaunchedEffect 收集数据流
    LaunchedEffect(repository) {
        repository.contactsFlow.collect { contacts ->
            childContacts = contacts
        }
    }

    var showEditDialog by remember { mutableStateOf(false) }
    var editingContact by remember { mutableStateOf<Contact?>(null) }
    var showAddContactDialog by remember { mutableStateOf(false) }

    // 长按相关状态
    var isLongPressing by remember { mutableStateOf(false) }
    var longPressProgress by remember { mutableStateOf(0f) }
    var showLocationMessage by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    var pressJob by remember { mutableStateOf<Job?>(null) }

    // 请求权限的启动器
    val requestPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val smsGranted = permissions[Manifest.permission.SEND_SMS] ?: false
        val locationGranted = (permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false) ||
                             (permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false)

        if (smsGranted && locationGranted) {
            sendLocationSms(context, childContacts, textToSpeech, activity)
        } else {
            textToSpeech.speak("需要短信和位置权限才能发送位置", TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(top = 64.dp, bottom = 16.dp, start = 16.dp, end = 16.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        // 短按（点击）- 重置状态
                        isLongPressing = false
                        longPressProgress = 0f
                        pressJob?.cancel()
                    },
                    onPress = {
                        // 按下时显示进度
                        isLongPressing = true
                        longPressProgress = 0f
                        showLocationMessage = false
                        
                        // 取消之前的任务
                        pressJob?.cancel()
                        
                        // 启动长按计时器（3 秒）
                        pressJob = coroutineScope.launch {
                            val startTime = System.currentTimeMillis()
                            val duration = 3000L // 3 秒
                            
                            try {
                                while (System.currentTimeMillis() - startTime < duration) {
                                    delay(50)
                                    val elapsed = System.currentTimeMillis() - startTime
                                    longPressProgress = (elapsed / duration.toFloat()).coerceIn(0f, 1f)
                                }
                                
                                // 3 秒后执行发送
                                isLongPressing = false
                                longPressProgress = 0f
                                showLocationMessage = true
                                
                                // 检查权限
                                val smsPermission = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.SEND_SMS
                                )
                                val locationPermission = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.ACCESS_FINE_LOCATION
                                )
                                
                                if (smsPermission == PackageManager.PERMISSION_GRANTED && 
                                    locationPermission == PackageManager.PERMISSION_GRANTED
                                ) {
                                    sendLocationSms(context, childContacts, textToSpeech, activity)
                                } else {
                                    requestPermissionLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.SEND_SMS,
                                            Manifest.permission.ACCESS_FINE_LOCATION,
                                            Manifest.permission.ACCESS_COARSE_LOCATION
                                        )
                                    )
                                }
                            } catch (e: Exception) {
                                // 被取消或异常
                                isLongPressing = false
                                longPressProgress = 0f
                            }
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // 120 按钮
                LargeContactButton(
                    label = "120",
                    phoneNumber = "120",
                    color = Color(0xFFF44336),
                    textToSpeech = textToSpeech,
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(16.dp))

                // 110 按钮
                LargeContactButton(
                    label = "110",
                    phoneNumber = "110",
                    color = Color(0xFF2196F3),
                    textToSpeech = textToSpeech,
                    modifier = Modifier.weight(1f)
                )
            }

            // 添加联系人按钮 - 置顶
            Button(
                onClick = { showAddContactDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF4CAF50)
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "添加联系人",
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text("添加家属联系人")
            }

            // 子女联系人列表
            // 显示所有子女联系人 - 瀑布流布局
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(childContacts) { contact ->
                    LargeContactButton(
                        label = contact.name,
                        phoneNumber = contact.phoneNumber,
                        color = contact.color,
                        textToSpeech = textToSpeech,
                        modifier = Modifier.fillMaxWidth(),
                        onLongClick = {
                            editingContact = contact
                            showEditDialog = true
                        }
                    )
                }
            }

            // 长按进度指示器和提示
            if (isLongPressing) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(top = 16.dp)
                ) {
                    LinearProgressIndicator(
                        progress = longPressProgress,
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.error,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Text(
                        text = "准备发送位置信息... (${(longPressProgress * 100).toInt()}%)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            } else if (showLocationMessage) {
                Text(
                    text = "✓ 正在发送位置信息",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                )
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                ) {
                    Text(
                        text = "💡 长按空白处 3 秒发送位置给紧急联系人",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "💡 长按联系人卡片可编辑信息",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }

    // 编辑联系人对话框
    if (showEditDialog && editingContact != null) {
        ContactEditDialog(
            contact = editingContact!!,
            onDismiss = {
                showEditDialog = false
                editingContact = null
            },
            onSave = { name, phoneNumber, color ->
                val updatedContact = editingContact!!.copy(
                    name = name,
                    phoneNumber = phoneNumber,
                    color = color
                )

                // 异步保存到 DataStore
                if (activity != null) {
                    activity.lifecycleScope.launch {
                        repository.updateContact(updatedContact)
                    }
                }

                showEditDialog = false
                editingContact = null
            },
            onDelete = {
                val contactToDelete = editingContact!!

                // 异步删除到 DataStore
                if (activity != null) {
                    activity.lifecycleScope.launch {
                        repository.deleteContact(contactToDelete.id)
                    }
                }

                showEditDialog = false
                editingContact = null
            }
        )
    }

    // 添加联系人对话框
    if (showAddContactDialog) {
        ContactEditDialog(
            contact = Contact(
                name = "",
                phoneNumber = "",
                color = Color(0xFFF44336) // 默认红色
            ),
            onDismiss = {
                showAddContactDialog = false
            },
            onSave = { name, phoneNumber, color ->
                val newContact = Contact(
                    name = name,
                    phoneNumber = phoneNumber,
                    color = color
                )

                // 异步保存到 DataStore
                if (activity != null) {
                    activity.lifecycleScope.launch {
                        repository.addContact(newContact)
                    }
                }

                showAddContactDialog = false
            },
            onDelete = null
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LargeContactButton(
    label: String,
    phoneNumber: String,
    color: Color,
    textToSpeech: TextToSpeech,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null
) {
    val context = LocalContext.current

    val requestPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted && phoneNumber.isNotEmpty()) {
            makePhoneCall(context, phoneNumber)
        }
    }

    Card(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(24.dp))
            .combinedClickable(
                onClick = {
                    // 播放语音提示
                    val speechText = if (phoneNumber.isNotEmpty()) {
                        "正在拨打$phoneNumber"
                    } else {
                        "正在拨打${label}的电话"
                    }
                    textToSpeech.speak(speechText, TextToSpeech.QUEUE_FLUSH, null, null)

                    // 拨打电话
                    if (phoneNumber.isNotEmpty()) {
                        if (androidx.core.content.ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.CALL_PHONE
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            makePhoneCall(context, phoneNumber)
                        } else {
                            requestPermissionLauncher.launch(Manifest.permission.CALL_PHONE)
                        }
                    }
                },
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 1f)
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 8.dp
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(color.copy(alpha = 1f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = Color.White,
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2
            )
        }
    }
}

private fun makePhoneCall(context: android.content.Context, phoneNumber: String) {
    try {
        val intent = Intent(Intent.ACTION_CALL).apply {
            data = Uri.parse("tel:$phoneNumber")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: SecurityException) {
        e.printStackTrace()
    }
}

private fun sendLocationSms(
    context: Context,
    contacts: List<Contact>,
    textToSpeech: TextToSpeech,
    activity: ComponentActivity?
) {
    if (contacts.isEmpty()) {
        textToSpeech.speak("没有紧急联系人，无法发送位置", TextToSpeech.QUEUE_FLUSH, null, null)
        return
    }

    textToSpeech.speak("正在获取位置并发送短信", TextToSpeech.QUEUE_FLUSH, null, null)

    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    // 检查 GPS 是否启用
    if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
        textToSpeech.speak("GPS 未开启，请打开位置服务", TextToSpeech.QUEUE_FLUSH, null, null)
        return
    }

    activity?.lifecycleScope?.launch {
        try {
            // 获取位置（优先 GPS，其次网络）
            val location = getLocationWithTimeout(locationManager, 10000L)

            if (location != null) {
                val latitude = location.latitude
                val longitude = location.longitude

                // 生成三个地图的网页版链接
                val baiduMapLink = "https://api.map.baidu.com/marker?location=$latitude,$longitude&title=紧急位置&content=求助&output=html"
                val gaodeMapLink = "https://uri.amap.com/marker?position=$longitude,$latitude&coordinate=wgs84"
                val tencentMapLink = "https://apis.map.qq.com/uri/v1/marker?marker=coord:$latitude,$longitude"

                // 构建短信内容 - 包含三个地图链接
                val message = buildString {
                    appendLine("【紧急求助】我需要帮助！")
                    appendLine()
                    appendLine("📍 我的位置：$latitude, $longitude")
                    appendLine()
                    appendLine("请点击以下任一链接查看位置：")
                    appendLine()
                    appendLine("百度地图：")
                    appendLine(baiduMapLink)
                    appendLine()
                    appendLine("高德地图：")
                    appendLine(gaodeMapLink)
                    appendLine()
                    appendLine("腾讯地图：")
                    appendLine(tencentMapLink)
                    appendLine()
                    appendLine("如果链接无法打开，请复制经纬度到导航软件")
                }

                // 发送短信给所有联系人
                val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.getSystemService(SmsManager::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    SmsManager.getDefault()
                }

                var successCount = 0
                contacts.forEach { contact ->
                    try {
                        // 分割长消息
                        val parts = smsManager.divideMessage(message)
                        smsManager.sendMultipartTextMessage(
                            contact.phoneNumber,
                            null,
                            parts,
                            null,
                            null
                        )
                        successCount++
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                textToSpeech.speak("已向$successCount 位联系人发送位置信息", TextToSpeech.QUEUE_FLUSH, null, null)
            } else {
                textToSpeech.speak("无法获取位置，请确保在室外开阔地带", TextToSpeech.QUEUE_FLUSH, null, null)
            }
        } catch (e: SecurityException) {
            textToSpeech.speak("没有位置权限，无法获取位置", TextToSpeech.QUEUE_FLUSH, null, null)
        } catch (e: Exception) {
            textToSpeech.speak("发送失败：${e.message}", TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }
}

// 检测已安装的地图 APP 并返回最佳链接
private fun getBestMapLink(context: Context, latitude: Double, longitude: Double): String {
    // 检测哪些地图 APP 已安装
    val installedMaps = mutableListOf<Triple<String, String, String>>()

    val mapApps = listOf(
        Triple("baidumap://map/marker?location=$latitude,$longitude&title=紧急位置&content=求助&src=android.app",
               "com.baidu.BaiduMap", "百度地图"),

        Triple("androidamap://viewMap?sourceApplication=紧急求助&poiname=我的位置&latlon=$latitude,$longitude&callnative=1",
               "com.autonavi.amap", "高德地图"),

        Triple("qqmap://map/marker?location=$latitude,$longitude&referer=紧急求助",
               "com.tencent.map", "腾讯地图"),

        Triple("google.navigation:q=$latitude,$longitude",
               "com.google.android.apps.maps", "Google 地图")
    )

    // 检测已安装的地图 APP
    for ((uriScheme, packageName, appName) in mapApps) {
        if (isAppInstalled(context, packageName)) {
            installedMaps.add(Triple(uriScheme, packageName, appName))
        }
    }

    return when {
        // 没有安装任何地图 APP - 默认使用高德网页版
        installedMaps.isEmpty() -> {
            "https://uri.amap.com/marker?position=$longitude,$latitude&coordinate=wgs84"
        }

        // 只安装了一个地图 APP - 直接使用该 APP 的 URI Scheme
        installedMaps.size == 1 -> {
            installedMaps[0].first
        }

        // 安装了多个地图 APP - 生成选择页面 HTML
        else -> {
            generateMapSelectionPage(latitude, longitude, installedMaps)
        }
    }
}

// 生成地图 APP 选择页面（使用 Data URI Scheme）
private fun generateMapSelectionPage(latitude: Double, longitude: Double, installedMaps: List<Triple<String, String, String>>): String {
    val htmlContent = buildString {
        append("<html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>")
        append("<style>")
        append("body{font-family:Arial,sans-serif;padding:20px;background:#f5f5f5;}")
        append(".container{max-width:400px;margin:0 auto;background:white;padding:20px;border-radius:8px;box-shadow:0 2px 8px rgba(0,0,0,0.1);}")
        append("h2{text-align:center;color:#333;margin-bottom:30px;}")
        append(".btn{display:block;width:100%;padding:15px;margin:10px 0;background:#2196F3;color:white;text-align:center;text-decoration:none;border-radius:6px;font-size:16px;font-weight:bold;}")
        append(".btn-baidu{background:#2979FF;}")
        append(".btn-gaode{background:#008CFF;}")
        append(".btn-tencent{background:#00D4A0;}")
        append(".btn-google{background:#4285F4;}")
        append(".btn:hover{opacity:0.8;}")
        append(".info{margin-top:20px;padding:15px;background:#f9f9f9;border-radius:6px;}")
        append(".info p{margin:5px 0;color:#666;}")
        append("</style></head><body>")
        append("<div class='container'>")
        append("<h2>🗺️ 选择地图查看位置</h2>")

        // 为每个已安装的地图 APP 生成按钮
        installedMaps.forEach { (uri, _, appName) ->
            val btnClass = when (appName) {
                "百度地图" -> "btn-baidu"
                "高德地图" -> "btn-gaode"
                "腾讯地图" -> "btn-tencent"
                "Google 地图" -> "btn-google"
                else -> ""
            }
            append("<a href='$uri' class='btn $btnClass'>📍 打开$appName</a>")
        }

        append("<div class='info'>")
        append("<p><strong>📍 经纬度坐标：</strong></p>")
        append("<p>纬度：$latitude</p>")
        append("<p>经度：$longitude</p>")
        append("<p style='margin-top:15px;font-size:12px;color:#999;'>如果以上按钮无法打开，请手动复制经纬度到地图 APP 搜索</p>")
        append("</div>")
        append("</div></body></html>")
    }

    // 使用 Data URI Scheme 将 HTML 编码为链接
    val encodedHtml = java.net.URLEncoder.encode(htmlContent, "UTF-8")
    return "data:text/html;charset=utf-8,$encodedHtml"
}

// 检查指定包名的应用是否已安装
private fun isAppInstalled(context: Context, packageName: String): Boolean {
    return try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}

private suspend fun getLocationWithTimeout(
    locationManager: LocationManager,
    timeoutMillis: Long
): Location? {
    return withTimeoutOrNull(timeoutMillis) {
        suspendCancellableCoroutine { continuation ->
            // 尝试从 GPS 获取位置
            val gpsListener = object : android.location.LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (!continuation.isCompleted) {
                        locationManager.removeUpdates(this)
                        continuation.resume(location)
                    }
                }

                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }

            // 注册监听器
            try {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    0L,
                    0f,
                    gpsListener
                )

                // 同时尝试网络定位作为备选
                val networkListener = object : android.location.LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (!continuation.isCompleted && location.accuracy < 1000) {
                            locationManager.removeUpdates(this)
                            continuation.resume(location)
                        }
                    }
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }

                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    0L,
                    0f,
                    networkListener
                )

                // 如果超时，取消协程
                continuation.invokeOnCancellation {
                    locationManager.removeUpdates(gpsListener)
                    locationManager.removeUpdates(networkListener)
                }
            } catch (e: SecurityException) {
                if (!continuation.isCompleted) {
                    continuation.resume(null)
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun EmergencyContactScreenPreview() {
    EAAServiceTheme {
        EmergencyContactScreen()
    }
}