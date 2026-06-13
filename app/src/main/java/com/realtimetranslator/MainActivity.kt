package com.realtimetranslator

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.realtimetranslator.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isServiceRunning = false

    private val serviceStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == TranslatorService.ACTION_SERVICE_STATE) {
                isServiceRunning = intent.getBooleanExtra(TranslatorService.EXTRA_RUNNING, false)
                updateServiceButton()
            }
        }
    }

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        updatePermissionStatuses()
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updatePermissionStatuses()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnStartStop.setOnClickListener {
            if (isServiceRunning) {
                stopTranslatorService()
            } else {
                if (allPermissionsGranted()) {
                    startTranslatorService()
                } else {
                    requestMissingPermissions()
                }
            }
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnRequestMic.setOnClickListener {
            requestMissingPermissions()
        }

        binding.btnRequestOverlay.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        }

        updateServiceButton()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatuses()

        val filter = IntentFilter(TranslatorService.ACTION_SERVICE_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(serviceStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(serviceStateReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(serviceStateReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver was not registered
        }
    }

    private fun updatePermissionStatuses() {
        val micGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        setPermissionStatus(binding.ivMicStatus, binding.tvMicStatus, micGranted)

        val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        setPermissionStatus(binding.ivNotifStatus, binding.tvNotifStatus, notifGranted)

        val overlayGranted = Settings.canDrawOverlays(this)
        setPermissionStatus(binding.ivOverlayStatus, binding.tvOverlayStatus, overlayGranted)

        val allGranted = micGranted && notifGranted && overlayGranted
        binding.tvSetupHint.text = if (allGranted) {
            getString(R.string.all_permissions_granted)
        } else {
            getString(R.string.grant_permissions_hint)
        }
    }

    private fun setPermissionStatus(icon: ImageView, label: TextView, granted: Boolean) {
        if (granted) {
            icon.setImageResource(android.R.drawable.presence_online)
            icon.setColorFilter(
                ContextCompat.getColor(this, R.color.permission_granted),
                android.graphics.PorterDuff.Mode.SRC_IN
            )
            label.text = getString(R.string.granted)
            label.setTextColor(ContextCompat.getColor(this, R.color.permission_granted))
        } else {
            icon.setImageResource(android.R.drawable.presence_busy)
            icon.setColorFilter(
                ContextCompat.getColor(this, R.color.permission_denied),
                android.graphics.PorterDuff.Mode.SRC_IN
            )
            label.text = getString(R.string.not_granted)
            label.setTextColor(ContextCompat.getColor(this, R.color.permission_denied))
        }
    }

    private fun allPermissionsGranted(): Boolean {
        val micGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val overlayGranted = Settings.canDrawOverlays(this)
        return micGranted && notifGranted && overlayGranted
    }

    private fun requestMissingPermissions() {
        val permissions = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissions.isNotEmpty()) {
            requestPermissionsLauncher.launch(permissions.toTypedArray())
        }

        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        }
    }

    private fun startTranslatorService() {
        val intent = Intent(this, TranslatorService::class.java)
        ContextCompat.startForegroundService(this, intent)
        isServiceRunning = true
        updateServiceButton()
    }

    private fun stopTranslatorService() {
        val intent = Intent(TranslatorService.ACTION_STOP_TRANSLATION)
        intent.setPackage(packageName)
        sendBroadcast(intent)
        isServiceRunning = false
        updateServiceButton()
    }

    private fun updateServiceButton() {
        if (isServiceRunning) {
            binding.btnStartStop.text = getString(R.string.stop_translator)
            binding.btnStartStop.setBackgroundResource(R.drawable.bg_pill_red)
        } else {
            binding.btnStartStop.text = getString(R.string.start_translator)
            binding.btnStartStop.setBackgroundResource(R.drawable.bg_pill_green)
        }
    }
}
