package com.example.s25_lab_listener

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * Fast Fourier Transform implementation for audio processing.
 *
 * Implements Cooley-Tukey FFT algorithm for power-of-2 sized inputs.
 */
object FFT {

    /**
     * Compute FFT of real-valued input.
     *
     * @param input Real-valued time domain signal (length must be power of 2)
     * @return Complex-valued frequency domain representation (as pairs of real/imaginary)
     */
    fun fft(input: FloatArray): Array<Pair<Float, Float>> {
        val n = input.size
        require(n > 0 && (n and (n - 1)) == 0) { "Input size must be a power of 2" }

        // Convert to complex pairs (real, imaginary)
        val complex = Array(n) { i -> Pair(input[i], 0f) }

        return fftComplex(complex)
    }

    /**
     * Compute FFT on complex input using Cooley-Tukey algorithm.
     */
    private fun fftComplex(input: Array<Pair<Float, Float>>): Array<Pair<Float, Float>> {
        val n = input.size

        if (n == 1) {
            return input
        }

        // Divide
        val even = Array(n / 2) { i -> input[2 * i] }
        val odd = Array(n / 2) { i -> input[2 * i + 1] }

        // Conquer
        val fftEven = fftComplex(even)
        val fftOdd = fftComplex(odd)

        // Combine
        val result = Array(n) { Pair(0f, 0f) }

        for (k in 0 until n / 2) {
            val angle = -2.0 * PI * k / n
            val wr = cos(angle).toFloat()
            val wi = sin(angle).toFloat()

            // Complex multiplication: (wr + wi*i) * (oddReal + oddImag*i)
            val oddReal = fftOdd[k].first
            val oddImag = fftOdd[k].second
            val tReal = wr * oddReal - wi * oddImag
            val tImag = wr * oddImag + wi * oddReal

            // Combine even and odd
            result[k] = Pair(
                fftEven[k].first + tReal,
                fftEven[k].second + tImag
            )
            result[k + n / 2] = Pair(
                fftEven[k].first - tReal,
                fftEven[k].second - tImag
            )
        }

        return result
    }

    /**
     * Compute power spectrum from FFT output.
     *
     * @param fftOutput Complex FFT output
     * @return Power spectrum (magnitude squared)
     */
    fun powerSpectrum(fftOutput: Array<Pair<Float, Float>>): FloatArray {
        return FloatArray(fftOutput.size) { i ->
            val real = fftOutput[i].first
            val imag = fftOutput[i].second
            real * real + imag * imag
        }
    }

    /**
     * Apply Hann window to signal.
     *
     * @param signal Input signal
     * @return Windowed signal
     */
    fun applyHannWindow(signal: FloatArray): FloatArray {
        val n = signal.size
        return FloatArray(n) { i ->
            val window = 0.5f * (1f - cos(2.0 * PI * i / (n - 1)).toFloat())
            signal[i] * window
        }
    }

    /**
     * Pad or truncate signal to target length.
     */
    fun padOrTruncate(signal: FloatArray, targetLength: Int): FloatArray {
        return when {
            signal.size == targetLength -> signal
            signal.size < targetLength -> FloatArray(targetLength) { i ->
                if (i < signal.size) signal[i] else 0f
            }
            else -> signal.copyOfRange(0, targetLength)
        }
    }

    /**
     * Find next power of 2 greater than or equal to n.
     */
    fun nextPowerOf2(n: Int): Int {
        var power = 1
        while (power < n) {
            power *= 2
        }
        return power
    }
}
