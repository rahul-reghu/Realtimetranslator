package com.realtimetranslator

import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions

class TranslationManager(direction: String) {

    private var translator: Translator? = null
    private var isModelReady = false
    private var pendingTranslations = mutableListOf<Triple<String, (String) -> Unit, (Exception) -> Unit>>()

    init {
        buildTranslator(direction)
    }

    private fun buildTranslator(direction: String) {
        translator?.close()
        isModelReady = false

        val (source, target) = if (direction == SettingsActivity.DIRECTION_ZH_TO_EN) {
            TranslateLanguage.CHINESE to TranslateLanguage.ENGLISH
        } else {
            TranslateLanguage.ENGLISH to TranslateLanguage.CHINESE
        }

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(source)
            .setTargetLanguage(target)
            .build()

        translator = Translation.getClient(options)
        downloadModelIfNeeded()
    }

    private fun downloadModelIfNeeded() {
        translator?.downloadModelIfNeeded()
            ?.addOnSuccessListener {
                isModelReady = true
                val pending = pendingTranslations.toList()
                pendingTranslations.clear()
                pending.forEach { (text, onSuccess, onFailure) ->
                    translateInternal(text, onSuccess, onFailure)
                }
            }
            ?.addOnFailureListener { exception ->
                pendingTranslations.forEach { (_, _, onFailure) ->
                    onFailure(exception)
                }
                pendingTranslations.clear()
            }
    }

    fun updateDirection(direction: String) {
        buildTranslator(direction)
    }

    // Alias for updateDirection for compatibility
    fun setDirection(direction: String) {
        buildTranslator(direction)
    }

    fun translate(text: String, onSuccess: (String) -> Unit, onFailure: (Exception) -> Unit) {
        if (text.isBlank()) {
            onSuccess("")
            return
        }

        if (!isModelReady) {
            pendingTranslations.add(Triple(text, onSuccess, onFailure))
            return
        }

        translateInternal(text, onSuccess, onFailure)
    }

    private fun translateInternal(text: String, onSuccess: (String) -> Unit, onFailure: (Exception) -> Unit) {
        translator?.translate(text)
            ?.addOnSuccessListener { translatedText ->
                onSuccess(translatedText)
            }
            ?.addOnFailureListener { exception ->
                onFailure(exception)
            }
            ?: onFailure(Exception("Translator not initialized"))
    }

    fun close() {
        translator?.close()
        translator = null
        pendingTranslations.clear()
    }
}
