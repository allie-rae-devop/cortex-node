We have achieved Inference execution, but it fails with: `Internal error: Failed to run on the given Interpreter: Input tensor 122 lacks data`.

This is a ByteBuffer size/shape mismatch.

Please FIX `AudioEngine.kt` and `MelSpectrogram.kt` to ensure perfect input alignment:

1. FIX `MelSpectrogram.kt`:
   - Ensure the output is strictly `FloatArray` of size `80 * 3000` (240,000 floats).
   - If the calculated spectrogram is smaller, PAD IT WITH ZEROS to exactly `80 * 3000`.
   - If larger, truncate it.
   - **Crucial:** The padding must happen on the *time* axis (3000 columns), not the frequency axis (80 rows).

2. FIX `AudioEngine.kt` (Input Buffer):
   - In `prepareInputBuffer`, ensure the `ByteBuffer` is allocated as `80 * 3000 * 4` (bytes per float).
   - Ensure `order(ByteOrder.nativeOrder())` is set.
   - **Verify:** Log `buffer.capacity()` before `run`. It MUST be exactly `960,000` bytes.

3. FIX `AudioEngine.kt` (Output Buffer):
   - We still see `Cannot convert ... INT32 and ... [[F`.
   - **Re-Verify:** Ensure `outputBuffer` is defined as `Array(1) { IntArray(448) }` (Integer array), NOT Float.

4. LOGGING:
   - Print `Input Buffer Size: $bytes` right before `interpreter.run`.

Implement these precise shape and type fixes.