package com.example.s25_lab_listener

import android.content.Context
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln
import kotlin.math.max

/**
 * MelSpectrogram converter for Whisper model preprocessing.
 *
 * Converts 16kHz PCM audio to Log-Mel Spectrogram with shape [1, 80, 3000].
 *
 * Process:
 * 1. Apply Hann window
 * 2. Compute FFT
 * 3. Apply 80 Mel filters (loaded from filters_vocab_gen.bin)
 * 4. Log-normalize the result
 *
 * @param context Android context for loading assets
 */
class MelSpectrogram(context: Context) {

    companion object {
        private const val TAG = "MelSpectrogram"

        // Whisper audio configuration
        private const val SAMPLE_RATE = 16000
        private const val N_FFT = 400  // FFT window size (25ms at 16kHz)
        private const val HOP_LENGTH = 160  // Hop size (10ms at 16kHz)
        private const val N_MELS = 80  // Number of mel filterbanks

        // Expected audio length for 30 seconds
        private const val AUDIO_LENGTH_SAMPLES = SAMPLE_RATE * 30  // 480,000 samples

        // Expected mel spectrogram time steps
        private const val MEL_TIME_STEPS = 3000

        // Mel filter file
        private const val MEL_FILTERS_FILE = "filters_vocab_gen.bin"

        // Small constant to avoid log(0)
        private const val LOG_EPSILON = 1e-10f
    }

    // Mel filter bank: [n_mels x (n_fft/2 + 1)]
    private var melFilters: Array<FloatArray>? = null

    init {
        loadMelFilters(context)
    }

    /**
     * Load pre-computed mel filters from binary file.
     *
     * File format: Custom binary file with "NESUP" header followed by 80 x 201 float32 values
     * (80 mel bins, 201 frequency bins for 400-point FFT)
     */
    private fun loadMelFilters(context: Context) {
        try {
            val inputStream = context.assets.open(MEL_FILTERS_FILE)

            // Read entire file into byte array
            val fileBytes = inputStream.readBytes()
            inputStream.close()

            // Skip the "NESUP" header (first 5 bytes)
            val headerSize = 5
            val dataBytes = fileBytes.copyOfRange(headerSize, fileBytes.size)

            // Create ByteBuffer with Little Endian byte order
            val byteBuffer = ByteBuffer.wrap(dataBytes)
            byteBuffer.order(ByteOrder.LITTLE_ENDIAN)

            val nFreqBins = (N_FFT / 2) + 1  // 201 frequency bins

            melFilters = Array(N_MELS) { FloatArray(nFreqBins) }

            // Read mel filter bank from buffer
            for (mel in 0 until N_MELS) {
                for (freq in 0 until nFreqBins) {
                    if (byteBuffer.remaining() >= 4) {
                        melFilters!![mel][freq] = byteBuffer.float
                    } else {
                        Log.e(TAG, "Insufficient data in mel filters file")
                        createFallbackFilters()
                        return
                    }
                }
            }

            Log.d(TAG, "Loaded mel filters: $N_MELS x $nFreqBins from binary file")

        } catch (e: Exception) {
            Log.e(TAG, "Error loading mel filters from $MEL_FILTERS_FILE", e)
            // Fallback: create identity/uniform filters if file missing
            createFallbackFilters()
        }
    }

    /**
     * Create fallback mel filters if binary file is not available.
     * This is a simplified approximation and won't give optimal results.
     */
    private fun createFallbackFilters() {
        Log.w(TAG, "Using fallback mel filters (not optimal)")

        val nFreqBins = (N_FFT / 2) + 1
        melFilters = Array(N_MELS) { FloatArray(nFreqBins) }

        // Simple triangular mel filters (simplified approximation)
        val melStep = nFreqBins.toFloat() / N_MELS

        for (mel in 0 until N_MELS) {
            val center = mel * melStep
            val left = max(0f, (mel - 1) * melStep)
            val right = ((mel + 1) * melStep).coerceAtMost(nFreqBins.toFloat())

            for (freq in 0 until nFreqBins) {
                melFilters!![mel][freq] = when {
                    freq < left || freq > right -> 0f
                    freq < center -> (freq - left) / (center - left)
                    freq > center -> (right - freq) / (right - center)
                    else -> 1f
                }
            }
        }
    }

    /**
     * Convert audio samples to Log-Mel Spectrogram.
     *
     * @param audioSamples 16kHz PCM audio (Short array)
     * @return Log-Mel Spectrogram [1, 80, 3000]
     */
    fun audioToMelSpectrogram(audioSamples: ShortArray): Array<Array<FloatArray>>? {
        if (melFilters == null) {
            Log.e(TAG, "Mel filters not loaded")
            return null
        }

        try {
            // Normalize audio to [-1.0, 1.0]
            val normalizedAudio = FloatArray(audioSamples.size) { i ->
                audioSamples[i].toFloat() / Short.MAX_VALUE.toFloat()
            }

            // Pad or truncate to expected length
            val paddedAudio = FFT.padOrTruncate(normalizedAudio, AUDIO_LENGTH_SAMPLES)

            // Compute STFT (Short-Time Fourier Transform) frames
            val numFrames = 1 + (paddedAudio.size - N_FFT) / HOP_LENGTH
            val melSpectrogram = Array(N_MELS) { FloatArray(numFrames) }

            for (frame in 0 until numFrames) {
                val start = frame * HOP_LENGTH
                val end = start + N_FFT

                if (end <= paddedAudio.size) {
                    try {
                        // Extract frame
                        val frameData = paddedAudio.copyOfRange(start, end)

                        // Apply Hann window
                        val windowedFrame = FFT.applyHannWindow(frameData)

                        // Pad to next power of 2 for FFT (Critical fix for math crash)
                        // Ensure nextPowerOfTwo is >= windowedFrame.size
                        var nextPowerOfTwo = Integer.highestOneBit(windowedFrame.size - 1) * 2
                        if (nextPowerOfTwo < windowedFrame.size) {
                            nextPowerOfTwo *= 2
                        }
                        val paddedFrame = FloatArray(nextPowerOfTwo)
                        windowedFrame.copyInto(paddedFrame, 0, 0, windowedFrame.size)

                        // Compute FFT (wrapped in try-catch for safety)
                        val fftOutput = FFT.fft(paddedFrame)

                        // Compute power spectrum
                        val powerSpec = FFT.powerSpectrum(fftOutput)

                        // Take only positive frequencies (n_fft/2 + 1) from ORIGINAL size
                        val nFreqBins = (N_FFT / 2) + 1
                        val halfPowerSpec = powerSpec.copyOfRange(0, nFreqBins)

                        // Apply mel filters
                        for (mel in 0 until N_MELS) {
                            var melValue = 0f
                            for (freq in 0 until nFreqBins) {
                                melValue += halfPowerSpec[freq] * melFilters!![mel][freq]
                            }

                            // Log-normalize (with small epsilon to avoid log(0))
                            melSpectrogram[mel][frame] = ln(melValue + LOG_EPSILON)
                        }
                    } catch (e: Exception) {
                        // Skip this frame if FFT fails, but continue processing
                        Log.w(TAG, "Failed to process frame $frame: ${e.message}")
                    }
                }
            }

            // Pad or truncate time dimension to 3000 steps
            val finalMelSpec = Array(N_MELS) { mel ->
                FFT.padOrTruncate(melSpectrogram[mel], MEL_TIME_STEPS)
            }

            // Reshape to [1, 80, 3000] for TFLite input
            return arrayOf(finalMelSpec)

        } catch (e: Exception) {
            Log.e(TAG, "Error computing mel spectrogram", e)
            return null
        }
    }

    /**
     * Convert normalized float audio to mel spectrogram.
     *
     * @param audioSamples Normalized float audio [-1.0, 1.0]
     * @return Log-Mel Spectrogram [1, 80, 3000]
     */
    fun floatAudioToMelSpectrogram(audioSamples: FloatArray): Array<Array<FloatArray>>? {
        // Convert float to short for consistency
        val shortAudio = ShortArray(audioSamples.size) { i ->
            (audioSamples[i] * Short.MAX_VALUE).toInt().toShort()
        }
        return audioToMelSpectrogram(shortAudio)
    }

    /**
     * Convert audio samples to Log-Mel Spectrogram as ByteBuffer.
     *
     * This method is optimized for TFLite inference - returns a flat ByteBuffer
     * ready to be passed directly to the interpreter.
     *
     * @param audioSamples 16kHz PCM audio (Short array)
     * @return ByteBuffer containing Log-Mel Spectrogram [1, 80, 3000] as flat float array
     */
    fun audioToMelSpectrogramBuffer(audioSamples: ShortArray): ByteBuffer? {
        // First compute the mel spectrogram using existing method
        val melSpecArray = audioToMelSpectrogram(audioSamples) ?: return null

        try {
            // Verify shape
            if (melSpecArray.size != 1) {
                Log.e(TAG, "Invalid batch dimension: ${melSpecArray.size}, expected 1")
                return null
            }
            if (melSpecArray[0].size != N_MELS) {
                Log.e(TAG, "Invalid mel dimension: ${melSpecArray[0].size}, expected $N_MELS")
                return null
            }
            if (melSpecArray[0][0].size != MEL_TIME_STEPS) {
                Log.e(TAG, "Invalid time dimension: ${melSpecArray[0][0].size}, expected $MEL_TIME_STEPS")
                return null
            }

            // Calculate total size: batch(1) x mels(80) x time_steps(3000) = 240,000 floats
            val totalFloats = N_MELS * MEL_TIME_STEPS
            val totalBytes = totalFloats * 4 // 960,000 bytes
            val byteBuffer = ByteBuffer.allocateDirect(totalBytes)
            byteBuffer.order(ByteOrder.nativeOrder())

            Log.d(TAG, "Creating ByteBuffer: $totalFloats floats = $totalBytes bytes")

            // Fill buffer in row-major order: [batch, mels, time_steps]
            for (mel in 0 until N_MELS) {
                for (time in 0 until MEL_TIME_STEPS) {
                    byteBuffer.putFloat(melSpecArray[0][mel][time])
                }
            }

            byteBuffer.rewind()
            return byteBuffer

        } catch (e: Exception) {
            Log.e(TAG, "Error creating ByteBuffer from mel spectrogram", e)
            return null
        }
    }
}
