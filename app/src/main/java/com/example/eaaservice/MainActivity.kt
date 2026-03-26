package com.example.eaaservice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.example.eaaservice.data.ContactRepository
import com.example.eaaservice.ui.theme.EAAServiceTheme
import java.util.Locale
import java.util.UUID

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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(top = 64.dp, bottom = 16.dp, start = 16.dp, end = 16.dp),
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
            
            Text(
                text = "💡 长按联系人卡片可编辑信息",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
            )
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

@Preview(showBackground = true)
@Composable
fun EmergencyContactScreenPreview() {
    EAAServiceTheme {
        EmergencyContactScreen()
    }
}