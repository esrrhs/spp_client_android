package com.esrrhs.spp.client

import android.Manifest
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.esrrhs.spp.client.spp.Profile
import com.esrrhs.spp.client.ui.AppPickerScreen
import com.esrrhs.spp.client.ui.LogsScreen
import androidx.lifecycle.lifecycleScope
import com.esrrhs.spp.client.ui.MainScreen
import com.esrrhs.spp.client.ui.MainViewModel
import com.esrrhs.spp.client.ui.ProfileEditorScreen
import com.esrrhs.spp.client.ui.SettingsScreen
import com.esrrhs.spp.client.ui.StatsScreen
import com.esrrhs.spp.client.ui.theme.SppClientTheme
import com.esrrhs.spp.client.util.ProfileShare
import com.esrrhs.spp.client.util.ProfilesFile
import com.esrrhs.spp.client.vpn.VpnController
import com.esrrhs.spp.client.vpn.VpnState
import com.esrrhs.spp.client.vpn.VpnStateHolder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private var pendingConnectProfileId: String? = null

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                startVpnService()
            } else {
                toast("未授予 VPN 权限，无法连接")
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            requestVpnPermissionAndStart()
        }

    /** 扫码（zxing-android-embedded 自带扫码界面与相机权限处理）。 */
    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents.isNullOrBlank()) return@registerForActivityResult
        val profile = ProfileShare.decode(contents)
        if (profile == null) {
            toast("二维码内容不是有效的 SPP 配置")
        } else {
            viewModel.importScanned(profile)
            toast("已导入配置：${profile.name}")
        }
    }

    /** 导出全部配置到用户选择的文件。 */
    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                val text = ProfilesFile.encode(viewModel.profiles.value)
                contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                toast("已导出 ${viewModel.profiles.value.size} 个配置")
            } catch (e: Exception) {
                toast("导出失败：${e.message}")
            }
        }

    /** 从文件导入配置。 */
    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                val text = contentResolver.openInputStream(uri)?.use {
                    it.readBytes().decodeToString()
                } ?: return@registerForActivityResult
                val list = ProfilesFile.decode(text)
                if (list.isEmpty()) {
                    toast("文件中没有有效配置")
                } else {
                    lifecycleScope.launch { viewModel.importProfiles(list) }
                    toast("已导入 ${list.size} 个配置")
                }
            } catch (e: Exception) {
                toast("导入失败：${e.message}")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // 来自 Quick Settings 磁贴：VPN 尚未授权时先打开本页，授权后立即连接
        if (intent?.getBooleanExtra(EXTRA_CONNECT, false) == true) {
            lifecycleScope.launch {
                val id = viewModel.awaitActiveProfileId()
                if (id != null) prepareConnectOrDisconnect(id) else toast("请先添加配置")
            }
        }

        setContent {
            SppClientTheme {
                var screen by rememberSaveable { mutableStateOf("list") }
                // 编辑中的草稿提升到 Activity，供 App 勾选器返回后保留
                var draft by remember { mutableStateOf(Profile()) }
                var editorIsNew by rememberSaveable { mutableStateOf(true) }
                var qrId by remember { mutableStateOf<String?>(null) }

                val profiles by viewModel.profiles.collectAsState()
                val activeId by viewModel.activeId.collectAsState()
                val vpnState by VpnStateHolder.state.collectAsState()
                val session by viewModel.session.collectAsState()
                val settings by viewModel.settings.collectAsState()
                val testingPings by viewModel.testingPings.collectAsState()

                when (screen) {
                    "logs" -> LogsScreen(onBack = { screen = "list" })

                    "stats" -> StatsScreen(
                        profiles = profiles,
                        activeId = activeId,
                        vpnState = vpnState,
                        session = session,
                        onResetTraffic = viewModel::resetTraffic,
                        onBack = { screen = "list" },
                    )

                    "settings" -> SettingsScreen(
                        settings = settings,
                        onChange = viewModel::updateSettings,
                        onBack = { screen = "list" },
                    )

                    "apppicker" -> AppPickerScreen(
                        initialSelected = draft.perAppPackages.toSet(),
                        onConfirm = { packages ->
                            draft = draft.copy(perAppPackages = packages.toList())
                            screen = "editor"
                        },
                        onBack = { screen = "editor" },
                    )

                    "editor" -> ProfileEditorScreen(
                        initial = draft,
                        isNew = editorIsNew,
                        onSave = { profile, callback ->
                            viewModel.saveProfile(profile) { error ->
                                if (error != null) toast(error)
                                callback(error)
                                if (error == null) screen = "list"
                            }
                        },
                        onPickApps = {
                            screen = "apppicker"
                        },
                        onBack = { screen = "list" },
                    )

                    else -> MainScreen(
                        profiles = profiles,
                        activeId = activeId,
                        vpnState = vpnState,
                        session = session,
                        testingPings = testingPings,
                        qrProfile = profiles.firstOrNull { it.id == qrId },
                        onProfileClick = ::prepareConnectOrDisconnect,
                        onEdit = { id ->
                            profiles.firstOrNull { it.id == id }?.let {
                                draft = it
                                editorIsNew = false
                                screen = "editor"
                            }
                        },
                        onDelete = viewModel::deleteProfile,
                        onAdd = {
                            draft = Profile()
                            editorIsNew = true
                            screen = "editor"
                        },
                        onShowLogs = { screen = "logs" },
                        onSettings = { screen = "settings" },
                        onScan = ::startScan,
                        onImport = {
                            importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                        },
                        onExport = { exportLauncher.launch("spp-profiles.json") },
                        onTestPing = viewModel::testPing,
                        onTestAll = viewModel::testAllPings,
                        onSelectFastest = viewModel::selectFastest,
                        onShowStats = { screen = "stats" },
                        onShowQr = { qrId = it },
                        onDismissQr = { qrId = null },
                    )
                }
            }
        }
    }

    private fun startScan() {
        val options = ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt("将配置二维码对准取景框")
            .setBeepEnabled(false)
            .setOrientationLocked(false)
        scanLauncher.launch(options)
    }

    /** 列表点击：正在使用的配置 → 断开；其他配置 → 校验权限后连接。 */
    private fun prepareConnectOrDisconnect(id: String) {
        val current = VpnStateHolder.state.value
        if (id == viewModel.activeId.value &&
            (current is VpnState.Connected || current is VpnState.Connecting)
        ) {
            VpnController.disconnect(this)
            return
        }
        pendingConnectProfileId = id
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        requestVpnPermissionAndStart()
    }

    private fun requestVpnPermissionAndStart() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        val id = pendingConnectProfileId ?: return
        viewModel.onProfileClicked(id)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        /** 由 Quick Settings 磁贴在需要 VPN 授权时携带：进页面后自动发起连接。 */
        const val EXTRA_CONNECT = "com.esrrhs.spp.client.extra.CONNECT"
    }
}
