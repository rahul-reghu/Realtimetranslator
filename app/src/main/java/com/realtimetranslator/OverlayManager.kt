package com.realtimetranslator

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import com.realtimetranslator.databinding.OverlayTranslatorBinding

class OverlayManager(private val context: Context) {

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private lateinit var binding: OverlayTranslatorBinding
    private var overlayView: View? = null
    private var isMinimized = false
    private var isShowing = false

    private lateinit var layoutParams: WindowManager.LayoutParams
    private var lastTranslatedText: String = ""

    var onTtsRequested: ((String) -> Unit)? = null
    var onLanguageToggled: (() -> Unit)? = null

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    fun show() {
        if (isShowing) return

        val themedContext = ContextThemeWrapper(context, R.style.Theme_RealTimeTranslator)
        val inflater = LayoutInflater.from(themedContext)
        binding = OverlayTranslatorBinding.inflate(inflater)
        overlayView = binding.root

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 100
        }

        setupTouchListener()
        setupButtons()

        windowManager.addView(overlayView, layoutParams)
        isShowing = true
    }

    fun hide() {
        overlayView?.visibility = View.GONE
    }

    fun remove() {
        if (!isShowing) return
        try {
            windowManager.removeView(overlayView)
        } catch (e: Exception) {
            // View may already be removed
        }
        isShowing = false
        overlayView = null
    }

    fun updateTranslation(source: String, translated: String) {
        overlayView?.post {
            binding.tvSourceText.text = source
            binding.tvTranslatedText.text = translated
            lastTranslatedText = translated
            if (isMinimized) {
                expandOverlay()
            }
        }
    }

    private fun setupTouchListener() {
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
                MotionEvent.ACTION_UP -> true
                else -> false
            }
        }
    }

    private fun setupButtons() {
        binding.btnTts.setOnClickListener {
            if (lastTranslatedText.isNotEmpty()) {
                onTtsRequested?.invoke(lastTranslatedText)
            }
        }

        binding.btnSwapLanguage.setOnClickListener {
            onLanguageToggled?.invoke()
        }

        binding.btnMinimize.setOnClickListener {
            if (isMinimized) {
                expandOverlay()
            } else {
                minimizeOverlay()
            }
        }

        binding.btnClose.setOnClickListener {
            remove()
            // Stop the service
            val stopIntent = android.content.Intent(TranslatorService.ACTION_STOP_TRANSLATION)
            context.sendBroadcast(stopIntent)
        }
    }

    private fun minimizeOverlay() {
        isMinimized = true
        binding.tvSourceText.visibility = View.GONE
        binding.tvTranslatedText.visibility = View.GONE
        binding.divider.visibility = View.GONE
        binding.btnTts.visibility = View.GONE
        binding.btnSwapLanguage.visibility = View.GONE
        binding.btnMinimize.setImageResource(android.R.drawable.arrow_up_float)
    }

    private fun expandOverlay() {
        isMinimized = false
        binding.tvSourceText.visibility = View.VISIBLE
        binding.tvTranslatedText.visibility = View.VISIBLE
        binding.divider.visibility = View.VISIBLE
        binding.btnTts.visibility = View.VISIBLE
        binding.btnSwapLanguage.visibility = View.VISIBLE
        binding.btnMinimize.setImageResource(android.R.drawable.arrow_down_float)
    }
}
