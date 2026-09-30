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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.esrrhs.spp.client.ui.LogsScreen
import com.esrrhs.spp.client.ui.MainScreen
import com.esrrhs.spp.client.ui.MainViewModel
import com.esrrhs.spp.client.ui.theme.SppClientTheme
import com.esrrhs.spp.client.vpn.VpnController
import com.esrrhs.spp.client.vpn.VpnStateHolder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

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
            // 通知权限被拒也可继续连接，只是看不到前台通知
            requestVpnPermissionAndStart()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            SppClientTheme {
                var showLogs by rememberSaveable { mutableStateOf(false) }
                val form by viewModel.form.collectAsState()
                val vpnState by VpnStateHolder.state.collectAsState()
                val saved by viewModel.saved.collectAsState()
                val traffic by viewModel.traffic.collectAsState()

                if (showLogs) {
                    LogsScreen(onBack = { showLogs = false })
                } else {
                    MainScreen(
                        form = form,
                        vpnState = vpnState,
                        saved = saved,
                        traffic = traffic,
                        onFormChange = viewModel::updateForm,
                        onConnect = ::onConnectClicked,
                        onDisconnect = { VpnController.disconnect(this) },
                        onSave = ::onSaveClicked,
                        onShowLogs = { showLogs = true },
                    )
                }
            }
        }
    }

    private fun onSaveClicked() {
        lifecycleScope.launch {
            val error = viewModel.validateAndSave()
            if (error != null) {
                toast(error)
                return@launch
            }
            // 「已保存」提示 2 秒后消失
            delay(2000)
            viewModel.clearSaved()
        }
    }

    private fun onConnectClicked() {
        lifecycleScope.launch {
            val error = viewModel.validateAndSave()
            if (error != null) {
                toast(error)
                return@launch
            }
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(
                    this@MainActivity, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return@launch
            }
            requestVpnPermissionAndStart()
        }
    }

    private fun requestVpnPermissionAndStart() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() = VpnController.connect(this)

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
