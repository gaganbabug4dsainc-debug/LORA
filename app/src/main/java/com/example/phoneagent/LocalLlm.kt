package com.example.phoneagent

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import java.io.File

/**
 * Phone ke andar chalne wala model (MediaPipe LLM Inference, `.task` file).
 * Koi internet ya quota nahi lagta. Model file ek baar import hoti hai (MoreActivity).
 * Note: MediaPipe ka sync generate beech me cancel nahi hota, isliye Stop dabane par
 * agent tab ruka dikhta hai jab current jawab poora ho jaye.
 */
object LocalLlm {
    @Volatile
    var appCtx: Context? = null

    private var engine: LlmInference? = null
    private var enginePath = ""
    private var engineTokens = 0

    fun modelsDir(c: Context): File = File(c.filesDir, "models").also { it.mkdirs() }

    /** Model ke jawab ke liye jagah chhodkar prompt ka max size (characters, andaza). */
    fun promptBudgetChars(c: Context): Int = ((Config.localCtx(c) - 220).coerceAtLeast(300)) * 3

    @Synchronized
    fun generate(path: String, system: String, prompt: String): String {
        val ctx = appCtx ?: throw ApiException(500, "[local] app context nahi mila")
        val f = File(path)
        if (!f.exists()) throw ApiException(404, "[local] model file nahi mili: ${f.name}")
        val tokens = Config.localCtx(ctx)

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
            val budget = promptBudgetChars(ctx)
            if (text.length > budget) {
                // Aakhri safety: beech ka hissa kaato (instructions + current screen bachte hain)
                text = text.take(budget * 2 / 5) + "\n...\n" + text.takeLast(budget * 3 / 5)
            }
            return engine!!.generateResponse(text)
        } catch (e: Throwable) {
            closeEngine()
            throw ApiException(500, "[local] ${e.javaClass.simpleName}: ${e.message?.take(120)}")
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

    /** Model family ke hisaab se chat template (file ke naam se pehchana jata hai). */
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
