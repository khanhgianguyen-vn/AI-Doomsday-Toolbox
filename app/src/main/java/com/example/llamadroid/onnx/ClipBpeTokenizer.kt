package com.example.llamadroid.onnx

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Locale

const val CLIP_MAX_TOKENS = 77
private const val CLIP_DEFAULT_BOS = 49406
private const val CLIP_DEFAULT_EOS = 49407
private const val WORD_END = "</w>"

data class ClipTokenization(
    val tokenIds: IntArray,
    val attentionMask: IntArray,
    val positionIds: IntArray,
    val tokenCount: Int,
    val wasTruncated: Boolean,
    val normalizedText: String
)

class ClipBpeTokenizer(
    vocabFile: File,
    mergesFile: File
) {
    private val vocab: Map<String, Int> = Json.parseToJsonElement(vocabFile.readText())
        .jsonObject
        .mapValues { (_, value) -> value.jsonPrimitive.int }
    private val byteEncoder = buildByteEncoder()
    private val bpeRanks: Map<Pair<String, String>, Int> = mergesFile.readLines()
        .dropWhile { it.startsWith("#") || it.isBlank() }
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .mapIndexedNotNull { index, line ->
            val parts = line.split(" ")
            if (parts.size >= 2) {
                (parts[0] to parts[1]) to index
            } else {
                null
            }
        }
        .toMap()
    private val bosToken = vocab["<|startoftext|>"] ?: CLIP_DEFAULT_BOS
    private val eosToken = vocab["<|endoftext|>"] ?: CLIP_DEFAULT_EOS
    private val unkToken = vocab["<|endoftext|>"] ?: eosToken
    private val cache = mutableMapOf<String, List<String>>()
    private val pattern = Regex(
        "<\\|startoftext\\|>|<\\|endoftext\\|>|'s|'t|'re|'ve|'m|'ll|'d| ?\\p{L}+| ?\\p{N}+| ?[^\\s\\p{L}\\p{N}]+|\\s+(?!\\S)|\\s+"
    )

    fun encode(prompt: String, maxLength: Int = CLIP_MAX_TOKENS): IntArray {
        return tokenize(prompt, maxLength).tokenIds
    }

    fun tokenize(prompt: String, maxLength: Int = CLIP_MAX_TOKENS): ClipTokenization {
        val normalizedPrompt = preprocessPrompt(prompt)
        val tokenIds = mutableListOf<Int>()
        tokenIds += bosToken
        pattern.findAll(normalizedPrompt).forEach { match ->
            val piece = match.value.trim()
            if (piece.isBlank()) return@forEach
            val encoded = piece.toByteArray(StandardCharsets.UTF_8).joinToString(separator = "") { byte ->
                byteEncoder[byte.toInt() and 0xFF].orEmpty()
            }
            val bpeTokens = cache.getOrPut(encoded) { applyBpe(encoded) }
            bpeTokens.forEach { token ->
                tokenIds += vocab[token] ?: unkToken
            }
        }
        tokenIds += eosToken

        val truncatedTokenIds = if (tokenIds.size >= maxLength) {
            tokenIds.take(maxLength - 1).toMutableList().apply { add(eosToken) }
        } else {
            tokenIds.toMutableList()
        }
        val tokenCount = truncatedTokenIds.size.coerceAtMost(maxLength)
        while (truncatedTokenIds.size < maxLength) {
            truncatedTokenIds += eosToken
        }
        return ClipTokenization(
            tokenIds = truncatedTokenIds.toIntArray(),
            attentionMask = IntArray(maxLength) { index -> if (index < tokenCount) 1 else 0 },
            positionIds = IntArray(maxLength) { index -> index },
            tokenCount = tokenCount,
            wasTruncated = tokenIds.size > maxLength,
            normalizedText = normalizedPrompt
        )
    }

    fun preprocessPrompt(prompt: String): String {
        return Normalizer.normalize(prompt, Normalizer.Form.NFKC)
            .lowercase(Locale.getDefault())
    }

    private fun applyBpe(token: String): List<String> {
        if (token.isEmpty()) return emptyList()
        val word = token.mapIndexed { index, char ->
            if (index == token.lastIndex) {
                "$char$WORD_END"
            } else {
                char.toString()
            }
        }.toMutableList()
        if (word.size == 1) {
            return word
        }

        while (true) {
            val pairs = getPairs(word)
            if (pairs.isEmpty()) break
            val bestPair = pairs.minByOrNull { bpeRanks[it] ?: Int.MAX_VALUE } ?: break
            if (bpeRanks[bestPair] == null) break

            val merged = mutableListOf<String>()
            var index = 0
            while (index < word.size) {
                if (
                    index < word.lastIndex &&
                    word[index] == bestPair.first &&
                    word[index + 1] == bestPair.second
                ) {
                    merged += word[index] + word[index + 1]
                    index += 2
                } else {
                    merged += word[index]
                    index += 1
                }
            }
            word.clear()
            word.addAll(merged)
            if (word.size == 1) break
        }

        return word
    }

    private fun getPairs(word: List<String>): Set<Pair<String, String>> {
        val pairs = linkedSetOf<Pair<String, String>>()
        for (index in 0 until word.lastIndex) {
            pairs += word[index] to word[index + 1]
        }
        return pairs
    }

    private fun buildByteEncoder(): Map<Int, String> {
        val bytes = mutableListOf<Int>()
        bytes += (33..126)
        bytes += (161..172)
        bytes += (174..255)

        val chars = bytes.toMutableList()
        var extra = 0
        for (value in 0..255) {
            if (value !in bytes) {
                bytes += value
                chars += 256 + extra
                extra += 1
            }
        }

        return bytes.indices.associate { index ->
            bytes[index] to chars[index].toChar().toString()
        }
    }
}
