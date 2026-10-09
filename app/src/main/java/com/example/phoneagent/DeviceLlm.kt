package com.example.phoneagent

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import java.io.File

/**
 * Phone ke andar chalne wala model (MediaPipe LLM Inference, `.task` / `.litertlm` file).
 * Internet aur quota nahi lagta. Model file Settings se import hoti hai (app ke private folder me).
 * Dhyan: sync generate beech me cancel nahi hota; Stop dabane par jawab poora hone par agent ruka dikhta hai.
 */
object DeviceLlm {
    private var engine: LlmInference? = null
    private var enginePath = ""
    private var engineTokens = 0

    fun modelsDir(c: Context): File = File(c.filesDir, "models").also { it.mkdirs() }

    /** Prompt ka max size (characters, andaza): jawab ke liye jagah chhodkar. */
    fun budgetChars(c: Context): Int = ((Config.deviceCtx(c) - 220).coerceAtLeast(300)) * 3

    @Synchronized
    fun generate(path: String, system: String, prompt: String): String {
        val ctx = Privacy.appCtx ?: throw ApiException(500, "[device] app context nahi mila")
        val f = File(path)
        if (!f.exists()) throw ApiException(404, "[device] model file nahi mili: ${f.name}")
        val tokens = Config.deviceCtx(ctx)
        try {
            if (engine == null || enginePath != path || engineTokens != tokens) {
                closeEngine()
                val opts = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(path)
                    .setMaxTokens(tokens)
                    .setMaxTopK(40)
                    .build()
                engine = LlmInference.createFromOptions(ctx, opts)
                enginePath = path
                engineTokens = tokens
            }
            var text = wrap(f.name, system, prompt)
            val budget = budgetChars(ctx)
            if (text.length > budget) {
                // Instructions + current screen bachte hain, beech ka hissa kat-ta hai
                text = text.take(budget * 2 / 5) + "\n...\n" + text.takeLast(budget * 3 / 5)
            }
            return engine!!.generateResponse(text)
        } catch (e: Throwable) {
            closeEngine()
            throw ApiException(500, "[device] ${e.javaClass.simpleName}: ${e.message?.take(120)}")
        }
    }

    @Synchronized
    fun release() {
        closeEngine()
    }

    private fun closeEngine() {
        try {
            engine?.close()
        } catch (_: Throwable) {
        }
        engine = null
        enginePath = ""
        engineTokens = 0
    }

    /** Model family ke hisaab se chat template (file ke naam se). */
    private fun wrap(fileName: String, system: String, prompt: String): String {
        val n = fileName.lowercase()
        return when {
            n.contains("gemma") ->
                "<start_of_turn>user\n$system\n\n$prompt<end_of_turn>\n<start_of_turn>model\n"
            n.contains("qwen") ->
                "<|im_start|>system\n$system<|im_end|>\n<|im_start|>user\n$prompt<|im_end|>\n<|im_start|>assistant\n"
            else -> "$system\n\n$prompt\n"
        }
    }
}
