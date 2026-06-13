package com.realtimetranslator

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
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

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
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

    fun updateLanguageLabel(label: String) {
        currentLanguageLabel = label.replace(" → ", "↔").replace(" to ", "↔")
        if (::binding.isInitialized) {
            overlayView?.post { updateSwapButton() }
        }
    }

    private fun updateSwapButton() {
        binding.btnSwapLanguage.text = currentLanguageLabel
    }

    private fun setupDragListener() {
        binding.dragHandle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams.x = initialX + (event.rawX - initialTouchX).toInt()
                    layoutParams.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(overlayView, layoutParams)
                    true
                }
                else -> false
            }
        }
    }

    private fun setupButtons() {
        binding.btnListenToggle.setOnClickListener {
            isListening = !isListening
            updateListenButton()
            if (!isListening) {
                // Collapsing — hide preview, keep translation visible
                binding.tvSourceText.visibility = View.GONE
            }
            onListenToggled?.invoke(isListening)
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

    private fun updateListenButton() {
        if (!::binding.isInitialized) return
        if (isListening) {
            binding.btnListenToggle.text = "⏹ TRANSLATE"
            binding.btnListenToggle.backgroundTintList = ColorStateList.valueOf(0xFFFF4D6D.toInt())
        } else {
            binding.btnListenToggle.text = "▶ LISTEN"
            binding.btnListenToggle.backgroundTintList = ColorStateList.valueOf(0xFF00C896.toInt())
        }
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
