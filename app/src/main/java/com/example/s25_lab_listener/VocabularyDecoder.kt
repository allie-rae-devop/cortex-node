package com.example.s25_lab_listener

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * VocabularyDecoder for converting Whisper token IDs to text.
 *
 * Loads vocabulary from vocab.txt (JSON format) and decodes model output tokens.
 *
 * @param context Android context for loading assets
 */
class VocabularyDecoder(context: Context) {

    companion object {
        private const val TAG = "VocabularyDecoder"
        private const val VOCAB_FILE = "vocab.txt"

        // Special tokens (Whisper-specific)
        private const val START_OF_TRANSCRIPT = 50258
        private const val END_OF_TRANSCRIPT = 50257
        private const val NO_SPEECH = 50362
        private const val TIMESTAMP_BEGIN = 50364
    }

    // Token ID to string mapping (inverted from JSON)
    private val vocabulary = mutableMapOf<Int, String>()

    init {
        loadVocabulary(context)
    }

    /**
     * Load vocabulary from vocab.txt file.
     *
     * File format: JSON object mapping "Token String" -> Integer ID
     * Example:
     * {
     *   "!": 0,
     *   "\"": 1,
     *   "#": 2,
     *   ...
     * }
     *
     * This function inverts the mapping to create ID -> Token String for decoding.
     */
    private fun loadVocabulary(context: Context) {
        try {
            // Read entire file as String
            val inputStream = context.assets.open(VOCAB_FILE)
            val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
            val jsonString = reader.use { it.readText() }

            // Parse JSON
            val jsonObject = JSONObject(jsonString)

            // Invert the mapping: "Token" -> ID becomes ID -> "Token"
            val keys = jsonObject.keys()
            while (keys.hasNext()) {
                val token = keys.next()
                val tokenId = jsonObject.getInt(token)

                // Store inverted mapping
                vocabulary[tokenId] = token
            }

            inputStream.close()

            Log.d(TAG, "Loaded vocabulary: ${vocabulary.size} tokens from JSON")

        } catch (e: Exception) {
            Log.e(TAG, "Error loading vocabulary from $VOCAB_FILE", e)
        }
    }

    /**
     * Decode token IDs to text string.
     *
     * @param tokenIds Array of token IDs from model output
     * @return Decoded text string
     */
    fun decode(tokenIds: IntArray): String {
        if (vocabulary.isEmpty()) {
            Log.w(TAG, "Vocabulary not loaded, cannot decode")
            return "[Vocabulary not loaded]"
        }

        val decodedTokens = mutableListOf<String>()

        for (tokenId in tokenIds) {
            // Skip special tokens
            when (tokenId) {
                START_OF_TRANSCRIPT -> continue
                END_OF_TRANSCRIPT -> break  // Stop decoding
                NO_SPEECH -> return ""  // No speech detected
                in TIMESTAMP_BEGIN..TIMESTAMP_BEGIN + 1500 -> continue  // Skip timestamps
            }

            // Look up token in vocabulary
            val token = vocabulary[tokenId]
            if (token != null) {
                decodedTokens.add(token)
            } else {
                Log.w(TAG, "Unknown token ID: $tokenId")
                decodedTokens.add("<unk>")
            }
        }

        // Join tokens and clean up
        return cleanupText(decodedTokens.joinToString(""))
    }

    /**
     * Decode from float array (argmax to find most likely token).
     *
     * @param logits Model output logits [sequence_length x vocab_size]
     * @return Decoded text string
     */
    fun decodeFromLogits(logits: Array<FloatArray>): String {
        val tokenIds = IntArray(logits.size) { i ->
            // Find argmax (most likely token)
            logits[i].indices.maxByOrNull { logits[i][it] } ?: 0
        }

        return decode(tokenIds)
    }

    /**
     * Decode from 2D float array (batch output).
     *
     * @param output Model output [batch_size x sequence_length x vocab_size]
     * @param batchIndex Which batch to decode (default 0)
     * @return Decoded text string
     */
    fun decodeFromBatchOutput(output: Array<Array<FloatArray>>, batchIndex: Int = 0): String {
        if (batchIndex >= output.size) {
            Log.e(TAG, "Invalid batch index: $batchIndex")
            return ""
        }

        return decodeFromLogits(output[batchIndex])
    }

    /**
     * Clean up decoded text.
     *
     * Removes special markers, fixes spacing, etc.
     */
    private fun cleanupText(text: String): String {
        return text
            .replace("Ġ", " ")  // Whisper uses Ġ for space
            .replace("Ċ", "\n")  // Whisper uses Ċ for newline
            .replace("<|endoftext|>", "")
            .replace("<|startoftranscript|>", "")
            .replace("<|notimestamps|>", "")
            .trim()
    }

    /**
     * Get vocabulary size.
     */
    fun getVocabSize(): Int {
        return vocabulary.size
    }

    /**
     * Check if vocabulary is loaded.
     */
    fun isLoaded(): Boolean {
        return vocabulary.isNotEmpty()
    }

    /**
     * Get token string by ID.
     */
    fun getToken(tokenId: Int): String? {
        return vocabulary[tokenId]
    }
}
