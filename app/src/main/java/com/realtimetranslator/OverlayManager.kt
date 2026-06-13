package com.realtimetranslator

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.realtimetranslator.databinding.OverlayTranslatorBinding

class OverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private lateinit var binding: OverlayTranslatorBinding
    private var overlayView: View? = null
    private var isMinimized = false
    private var isShowing = false
    private var isListening = false
    private var isPrivacyMode = false
    private var currentLanguageLabel = "ZH↔EN"
    private lateinit var layoutParams: WindowManager.LayoutParams
    private var lastTranslatedText = ""

    var onTtsRequested: ((String) -> Unit)? = null
    var onLanguageToggled: (() -> Unit)? = null
    var onListenToggled: ((Boolean) -> Unit)? = null

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    fun show() {
        if (isShowing) return

        val themedContext = ContextThemeWrapper(context, R.style.Theme_RealTimeTranslator)
        binding = OverlayTranslatorBinding.inflate(LayoutInflater.from(themedContext))
        overlayView = binding.root

        val widthPx = (180 * context.resources.displayMetrics.density).toInt()
        layoutParams = WindowManager.LayoutParams(
            widthPx,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 80
        }

        setupDragListener()
        setupButtons()
        updateListenButton()
        updateSwapButton()
        updatePrivacyButton()

        windowManager.addView(overlayView, layoutParams)
        isShowing = true
    }

    fun remove() {
        if (!isShowing) return
        try { windowManager.removeView(overlayView) } catch (e: Exception) { }
        isShowing = false
        overlayView = null
    }

    fun updateTranslation(source: String, translated: String) {
        overlayView?.post {
            if (translated.isBlank()) {
                binding.tvSourceText.visibility = View.GONE
                binding.tvTranslatedText.visibility = View.GONE
                binding.btnTts.visibility = View.GONE
            } else {
                binding.tvSourceText.text = source
                binding.tvSourceText.visibility = if (source.isNotBlank()) View.VISIBLE else View.GONE
                binding.tvTranslatedText.text = translated
                binding.tvTranslatedText.visibility = View.VISIBLE
                binding.btnTts.visibility = View.VISIBLE
                lastTranslatedText = translated
                if (isMinimized) expandOverlay()
            }
        }
    }

    fun updateSourcePreview(text: String) {
        overlayView?.post {
            if (text.isBlank()) {
                binding.tvSourceText.visibility = View.GONE
            } else {
                binding.tvSourceText.text = text
                binding.tvSourceText.visibility = View.VISIBLE
            }
        }
    }

    fun updateMicLevel(rmsdB: Float) {
        if (!::binding.isInitialized) return
        overlayView?.post {
            if (!isListening) return@post
            // Map rms 0..10 to alpha and color
            val level = (rmsdB / 10f).coerceIn(0f, 1f)
            val baseAlpha = 80
            val alpha = (baseAlpha + (175 * level)).toInt().coerceIn(0, 255)
            val green = (0x88 + (0x77 * level)).toInt().coerceIn(0, 255)
            binding.tvMicIndicator.setTextColor(Color.argb(alpha, 0, green, 0x7A))
        }
    }

    fun showMicBusyError() {
        overlayView?.post {
            isListening = false
            updateListenButton()
            binding.tvSourceText.visibility = View.GONE
            binding.tvTranslatedText.text = "⚠️ Mic busy — enable speakerphone in your call app, then tap LISTEN again"
            binding.tvTranslatedText.visibility = View.VISIBLE
            binding.btnTts.visibility = View.GONE
        }
    }

    fun updateLanguageLabel(label: String) {
        currentLanguageLabel = label.replace(" → ", "↔").replace(" to ", "↔")
        if (::binding.isInitialized) {
            overlayView?.post { updateSwapButton() }
        }
    }

    private fun updateSwapButton() {
        binding.btnSwapLanguage.text = currentLanguageLabel
    }

    private var isDragging = false
    private val dragThreshold = 10f

    private fun setupDragListener() {
        binding.cardOverlay.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (!isDragging && (Math.abs(dx) > dragThreshold || Math.abs(dy) > dragThreshold)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        layoutParams.x = initialX + dx.toInt()
                        layoutParams.y = initialY + dy.toInt()
                        windowManager.updateViewLayout(overlayView, layoutParams)
                    }
                    isDragging
                }
                MotionEvent.ACTION_UP -> {
                    val wasDragging = isDragging
                    isDragging = false
                    wasDragging
                }
                else -> false
            }
        }
    }

    private fun setupButtons() {
        // Privacy mode toggle
        binding.btnPrivacyMode.setOnClickListener {
            isPrivacyMode = !isPrivacyMode
            updatePrivacyButton()
            // If switching off privacy mode while listening, stop
            if (!isPrivacyMode && isListening) {
                isListening = false
                updateListenButton()
                onListenToggled?.invoke(false)
            }
        }

        // Listen button — behaviour depends on mode
        binding.btnListenToggle.setOnClickListener {
            if (!isPrivacyMode) {
                // Normal toggle mode
                isListening = !isListening
                updateListenButton()
                if (!isListening) binding.tvSourceText.visibility = View.GONE
                onListenToggled?.invoke(isListening)
            }
            // In privacy mode, clicks are ignored — only hold works
        }

        // Hold-to-listen for privacy mode
        binding.btnListenToggle.setOnTouchListener { _, event ->
            if (!isPrivacyMode) return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (!isListening) {
                        isListening = true
                        updateListenButton()
                        onListenToggled?.invoke(true)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isListening) {
                        isListening = false
                        updateListenButton()
                        binding.tvSourceText.visibility = View.GONE
                        onListenToggled?.invoke(false)
                    }
                    true
                }
                else -> false
            }
        }

        binding.btnSwapLanguage.setOnClickListener {
            onLanguageToggled?.invoke()
        }

        binding.btnTts.setOnClickListener {
            if (lastTranslatedText.isNotEmpty()) onTtsRequested?.invoke(lastTranslatedText)
        }

        binding.btnMinimize.setOnClickListener {
            if (isMinimized) expandOverlay() else minimizeOverlay()
        }

        binding.btnClose.setOnClickListener {
            remove()
            context.sendBroadcast(
                Intent(TranslatorService.ACTION_STOP_TRANSLATION).apply { setPackage(context.packageName) }
            )
        }
    }

    private fun updatePrivacyButton() {
        if (!::binding.isInitialized) return
        if (isPrivacyMode) {
            binding.btnPrivacyMode.alpha = 1.0f
            binding.tvPrivacyHint.visibility = View.VISIBLE
            // Switch listen button label to hold mode
            binding.btnListenToggle.text = "🎧 HOLD"
            binding.btnListenToggle.backgroundTintList = ColorStateList.valueOf(0xFF6C63FF.toInt())
        } else {
            binding.btnPrivacyMode.alpha = 0.4f
            binding.tvPrivacyHint.visibility = View.GONE
            updateListenButton()
        }
    }

    private fun updateListenButton() {
        if (!::binding.isInitialized) return
        if (isPrivacyMode) {
            binding.btnListenToggle.text = if (isListening) "🎧 ON" else "🎧 HOLD"
            binding.btnListenToggle.backgroundTintList = ColorStateList.valueOf(
                if (isListening) 0xFFFF4D6D.toInt() else 0xFF6C63FF.toInt()
            )
        } else {
            if (isListening) {
                binding.btnListenToggle.text = "⏹ TRANSLATE"
                binding.btnListenToggle.backgroundTintList = ColorStateList.valueOf(0xFFFF4D6D.toInt())
            } else {
                binding.btnListenToggle.text = "▶ LISTEN"
                binding.btnListenToggle.backgroundTintList = ColorStateList.valueOf(0xFF00C896.toInt())
            }
        }
        binding.tvMicIndicator.visibility = if (isListening) View.VISIBLE else View.GONE
    }

    private fun minimizeOverlay() {
        isMinimized = true
        binding.tvSourceText.visibility = View.GONE
        binding.tvTranslatedText.visibility = View.GONE
        binding.btnTts.visibility = View.GONE
        binding.btnMinimize.setImageResource(android.R.drawable.arrow_down_float)
    }

    private fun expandOverlay() {
        isMinimized = false
        binding.btnMinimize.setImageResource(android.R.drawable.arrow_up_float)
        if (lastTranslatedText.isNotEmpty()) {
            binding.tvTranslatedText.visibility = View.VISIBLE
            binding.btnTts.visibility = View.VISIBLE
        }
    }
}
