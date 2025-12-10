package com.example.s25_lab_listener

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * AudioEngine handles Whisper TensorFlow Lite model inference.
 *
 * Whisper-specific pipeline:
 * 1. Convert 16kHz PCM audio to Log-Mel Spectrogram [1, 80, 3000]
 * 2. Feed mel spectrogram to TFLite interpreter
 * 3. Decode output token IDs to text using vocabulary
 *
 * Uses GPU Delegate (QNN acceleration on Snapdragon 8 Elite).
 */
class AudioEngine(private val context: Context) {

    companion object {
        private const val TAG = "AudioEngine"

        // Whisper model file
        private const val MODEL_PATH = "whisper.tflite"

        // Whisper expects 30-second chunks at 16kHz
        private const val WHISPER_SAMPLE_RATE = 16000
        private const val WHISPER_CHUNK_DURATION_SECONDS = 30
        private const val WHISPER_CHUNK_SIZE = WHISPER_SAMPLE_RATE * WHISPER_CHUNK_DURATION_SECONDS

        // Sliding window inference trigger (every 3 seconds)
        private const val INFERENCE_TRIGGER_SECONDS = 3
        private const val INFERENCE_TRIGGER_SAMPLES = WHISPER_SAMPLE_RATE * INFERENCE_TRIGGER_SECONDS

        // Mel spectrogram dimensions
        private const val N_MELS = 80
        private const val MEL_TIME_STEPS = 3000

        // Audio accumulation buffer capacity
        private const val BUFFER_CAPACITY = WHISPER_CHUNK_SIZE * 2
    }

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null

    // Model readiness flag (prevents race condition crashes)
    @Volatile
    private var isModelReady = false

    // Mel spectrogram converter
    private var melSpectrogram: MelSpectrogram? = null

    // Vocabulary decoder
    private var vocabularyDecoder: VocabularyDecoder? = null

    // Accumulated audio buffer for sliding window (up to 30 seconds)
    private val audioAccumulator = mutableListOf<Short>()

    // Track samples added since last inference (for sliding window)
    private var samplesSinceLastInference = 0

    init {
        initializeModel()
        initializeHelpers()

        // Set model ready ONLY if all components initialized successfully
        if (interpreter != null && melSpectrogram != null && vocabularyDecoder != null) {
            isModelReady = true
            Log.d(TAG, "AudioEngine fully initialized and ready")
        } else {
            isModelReady = false
            Log.w(TAG, "AudioEngine initialization incomplete - not ready")
        }
    }

    /**
     * Initialize TFLite interpreter with GPU delegate.
     */

    private fun initializeModel() {
        try {
            // Check GPU compatibility
            val compatList = CompatibilityList()
            val options = Interpreter.Options()

            if (compatList.isDelegateSupportedOnThisDevice) {
                // Use GPU delegate for QNN/GPU acceleration on Snapdragon 8 Elite
                // Fix: Removed incorrect usage of deprecated GpuDelegate.Options methods
                gpuDelegate = GpuDelegate()
                options.addDelegate(gpuDelegate)
                Log.d(TAG, "GPU Delegate enabled for Whisper TFLite")
            } else {
                Log.w(TAG, "GPU Delegate not supported, using CPU")
                options.setNumThreads(4)
            }

            // Load Whisper model from assets
            val model = loadModelFile(MODEL_PATH)
            interpreter = Interpreter(model, options)

            Log.d(TAG, "Whisper TFLite model initialized successfully")
            logModelInfo()

        } catch (e: Exception) {
            Log.e(TAG, "Error initializing Whisper TFLite model", e)
            isModelReady = false
        }
    }

    /**
     * Initialize mel spectrogram converter and vocabulary decoder.
     */
    private fun initializeHelpers() {
        try {
            // Initialize mel spectrogram converter
            melSpectrogram = MelSpectrogram(context)
            Log.d(TAG, "MelSpectrogram converter initialized")

            // Initialize vocabulary decoder (loads vocab.txt)
            vocabularyDecoder = VocabularyDecoder(context)
            Log.d(TAG, "VocabularyDecoder initialized with ${vocabularyDecoder?.getVocabSize()} tokens")

        } catch (e: Exception) {
            Log.e(TAG, "Error initializing helper components", e)
        }
    }

    private fun loadModelFile(modelPath: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelPath)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    private fun logModelInfo() {
        interpreter?.let { interp ->
            Log.d(TAG, "Model input count: ${interp.inputTensorCount}")
            Log.d(TAG, "Model output count: ${interp.outputTensorCount}")

            for (i in 0 until interp.inputTensorCount) {
                val inputShape = interp.getInputTensor(i).shape()
                val inputType = interp.getInputTensor(i).dataType()
                Log.d(TAG, "Input $i shape: ${inputShape.contentToString()}, type: $inputType")
            }

            for (i in 0 until interp.outputTensorCount) {
                val outputShape = interp.getOutputTensor(i).shape()
                val outputType = interp.getOutputTensor(i).dataType()
                Log.d(TAG, "Output $i shape: ${outputShape.contentToString()}, type: $outputType")
            }
        }
    }

    /**
     * Process an audio chunk through the Whisper model using sliding window inference.
     *
     * New Approach:
     * - Accumulates audio samples continuously in a rolling buffer (up to 30s max)
     * - Triggers inference every 3 seconds of new audio
     * - Uses entire current accumulator (padded to 30s if shorter)
     * - Preserves context by keeping audio in buffer (discards only audio older than 30s)
     *
     * @param audioData Raw PCM audio samples (16-bit signed, 16kHz)
     * @param size Number of valid samples in audioData
     * @return Transcribed text, or null if not enough audio accumulated yet
     */
    fun processAudioChunk(audioData: ShortArray, size: Int): String? {
        // Silent guard: return null if model not ready (prevents log spam during initialization)
        if (!isModelReady) return null

        // Accumulate audio samples
        synchronized(audioAccumulator) {
            // Add new samples to accumulator
            for (i in 0 until size) {
                audioAccumulator.add(audioData[i])
            }

            // Track samples added since last inference
            samplesSinceLastInference += size

            // Maintain rolling buffer: discard audio older than 30 seconds
            while (audioAccumulator.size > WHISPER_CHUNK_SIZE) {
                audioAccumulator.removeAt(0)
            }

            // Trigger inference every 3 seconds of new audio
            if (samplesSinceLastInference >= INFERENCE_TRIGGER_SAMPLES) {
                Log.d(TAG, "Sliding window trigger: ${audioAccumulator.size} samples accumulated")

                // Take entire current accumulator
                val chunk = audioAccumulator.toShortArray()

                // Reset inference counter (but keep audio in buffer for context)
                samplesSinceLastInference = 0

                // Run Whisper inference on this chunk (will be padded to 30s if needed)
                return runInference(chunk)
            }

            // Not time to infer yet
            return null
        }
    }

    /**
     * Get current buffer size (for UI display).
     */
    fun getBufferSize(): Int {
        synchronized(audioAccumulator) {
            return audioAccumulator.size
        }
    }

    /**
     * Run Whisper inference on audio chunk.
     *
     * Step 1: Convert audio to Log-Mel Spectrogram ByteBuffer
     * Step 2: Pass mel spectrogram to TFLite interpreter
     * Step 3: Decode output tokens to text
     */
    private fun runInference(audioChunk: ShortArray): String? {
        // Check if all components are initialized
        if (interpreter == null || melSpectrogram == null || vocabularyDecoder == null) {
            Log.e(TAG, "Cannot run inference: Components not initialized")
            return null
        }

        try {
            val startTime = System.currentTimeMillis()

            // STEP 1: Convert audio to Log-Mel Spectrogram ByteBuffer [1, 80, 3000]
            val inputBuffer = melSpectrogram?.audioToMelSpectrogramBuffer(audioChunk)
            if (inputBuffer == null) {
                Log.e(TAG, "Failed to compute mel spectrogram")
                return null
            }

            val melTime = System.currentTimeMillis()
            Log.d(TAG, "Mel spectrogram computed in ${melTime - startTime}ms")

            // Verify input buffer size
            Log.d(TAG, "Input Buffer Size: ${inputBuffer.capacity()} bytes (expected 960000)")
            Log.d(TAG, "Input Buffer position: ${inputBuffer.position()}, limit: ${inputBuffer.limit()}")

            // Log all input tensor requirements
            for (i in 0 until interpreter!!.inputTensorCount) {
                val inputTensor = interpreter!!.getInputTensor(i)
                Log.d(TAG, "Input Tensor $i: shape=${inputTensor.shape().contentToString()}, type=${inputTensor.dataType()}")
            }

            // STEP 2: Prepare output buffer
            // Note: Output shape depends on model - typically [batch, sequence_length, vocab_size]
            // For Whisper, sequence_length is variable but often ~448 for 30s audio
            val outputTensor = interpreter!!.getOutputTensor(0)
            val outputShape = outputTensor.shape()
            val outputType = outputTensor.dataType()
            Log.d(TAG, "Output tensor shape: ${outputShape.contentToString()}, type: $outputType")

            // Allocate output buffer based on actual model output shape
            val output = allocateOutputBuffer(outputShape)
            Log.d(TAG, "Output buffer type: ${output::class.java.simpleName}")

            // STEP 3: Run inference
            Log.d(TAG, "Running inference...")
            interpreter?.run(inputBuffer, output)

            val inferenceTime = System.currentTimeMillis()
            Log.d(TAG, "Inference completed in ${inferenceTime - melTime}ms")

            // STEP 4: Decode output tokens to text
            val transcription = decodeOutput(output)

            val totalTime = System.currentTimeMillis() - startTime
            Log.d(TAG, "Total processing time: ${totalTime}ms")

            return transcription

        } catch (e: Exception) {
            Log.e(TAG, "Error during Whisper inference", e)
            return null
        }
    }

    /**
     * Prepare mel spectrogram as ByteBuffer for TFLite input.
     *
     * Input shape: [1, 80, 3000]
     */
    private fun prepareInputBuffer(melSpec: Array<Array<FloatArray>>): ByteBuffer {
        // Mel spec shape: [batch=1, n_mels=80, time_steps=3000]
        val batch = melSpec.size
        val mels = melSpec[0].size
        val timeSteps = melSpec[0][0].size

        val byteBuffer = ByteBuffer.allocateDirect(batch * mels * timeSteps * 4) // 4 bytes per float
        byteBuffer.order(ByteOrder.nativeOrder())

        // Fill buffer in row-major order
        for (b in 0 until batch) {
            for (m in 0 until mels) {
                for (t in 0 until timeSteps) {
                    byteBuffer.putFloat(melSpec[b][m][t])
                }
            }
        }

        byteBuffer.rewind()
        return byteBuffer
    }

    /**
     * Allocate output buffer based on model's output shape.
     *
     * CRITICAL: Whisper outputs INT32 token IDs, not floats!
     */
    private fun allocateOutputBuffer(outputShape: IntArray): Any {
        return when (outputShape.size) {
            1 -> IntArray(outputShape[0])
            2 -> Array(outputShape[0]) { IntArray(outputShape[1]) }
            3 -> Array(outputShape[0]) { Array(outputShape[1]) { IntArray(outputShape[2]) } }
            else -> {
                Log.w(TAG, "Unexpected output shape: ${outputShape.contentToString()}")
                IntArray(1)
            }
        }
    }

    /**
     * Decode Whisper model output to text.
     *
     * CRITICAL: Model outputs INT32 token IDs directly (not logits).
     */
    private fun decodeOutput(output: Any): String {
        try {
            // Handle different output shapes (all using IntArray now)
            when (output) {
                is Array<*> -> {
                    // Check if it's Array<Array<IntArray>> (3D)
                    if (output.isArrayOf<Array<*>>()) {
                        @Suppress("UNCHECKED_CAST")
                        val output3D = output as Array<Array<IntArray>>
                        // For 3D output, take first batch
                        return if (output3D.isNotEmpty() && output3D[0].isNotEmpty()) {
                            vocabularyDecoder?.decode(output3D[0][0]) ?: ""
                        } else {
                            ""
                        }
                    }
                    // Check if it's Array<IntArray> (2D)
                    else if (output.isArrayOf<IntArray>()) {
                        @Suppress("UNCHECKED_CAST")
                        val output2D = output as Array<IntArray>
                        // For 2D output, take first sequence
                        return if (output2D.isNotEmpty()) {
                            vocabularyDecoder?.decode(output2D[0]) ?: ""
                        } else {
                            ""
                        }
                    }
                }
                is IntArray -> {
                    // Single sequence of token IDs
                    return vocabularyDecoder?.decode(output) ?: ""
                }
            }

            Log.w(TAG, "Unexpected output type: ${output::class.java.simpleName}")
            return ""

        } catch (e: Exception) {
            Log.e(TAG, "Error decoding output", e)
            return ""
        }
    }

    /**
     * Clear the audio accumulator.
     * Call this to reset the buffering state.
     */
    fun clearBuffer() {
        synchronized(audioAccumulator) {
            audioAccumulator.clear()
            samplesSinceLastInference = 0
        }
        Log.d(TAG, "Audio buffer cleared")
    }

    /**
     * Release TFLite resources.
     * Call this when the engine is no longer needed.
     */
    fun release() {
        try {
            interpreter?.close()
            interpreter = null

            gpuDelegate?.close()
            gpuDelegate = null

            audioAccumulator.clear()

            Log.d(TAG, "AudioEngine resources released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioEngine resources", e)
        }
    }
}
