package com.example.replyassist

import android.content.Context
import org.json.JSONObject

/**
 * Learns two things locally, entirely on-device (nothing here is sent
 * anywhere by itself — it's only summarized into a short text string that
 * gets included in the Claude prompt at suggestion time):
 *
 * 1. "내 말투" (my own voice): aggregated from message bubbles the
 *    accessibility service identifies as mine, across every chat it reads.
 * 2. Per-partner closeness: aggregated from the other person's bubbles in
 *    a given chat room, keyed by a rough partner identifier (chat title).
 *
 * This is a lightweight running-count heuristic, not a trained model — but
 * it improves ("learns") the more you use the floating button, since counts
 * accumulate across sessions via SharedPreferences.
 */
object StyleProfileStore {

    private const val PREFS = "reply_assist_style_prefs"
    private const val KEY_MY_STYLE = "my_style"
    private const val PARTNER_PREFIX = "partner_"

    // Rough emoji / emoticon detector. Covers common pictographs and the
    // ㅋㅋ/ㅎㅎ laugh markers that function like emoji in Korean chat.
    private val EMOJI_REGEX = Regex("[\uD83C-\uD83E][\uDC00-\uDFFF]|[\u2600-\u27BF]|❤|♥|ㅋㅋ+|ㅎㅎ+|ㅠㅠ+|ㅜㅜ+")
    private val FORMAL_ENDINGS = listOf("습니다", "해요", "예요", "네요", "세요", "죠", "합니다")

    private data class Stats(
        var totalMessages: Int = 0,
        var formalCount: Int = 0,
        var informalCount: Int = 0,
        var emojiOccurrences: Int = 0,
        var totalLength: Int = 0,
        val topEmoji: MutableMap<String, Int> = mutableMapOf()
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(context: Context, key: String): Stats {
        val json = prefs(context).getString(key, null) ?: return Stats()
        return try {
            val obj = JSONObject(json)
            val emojiMap = mutableMapOf<String, Int>()
            obj.optJSONObject("topEmoji")?.let { emojiObj ->
                emojiObj.keys().forEach { k -> emojiMap[k] = emojiObj.getInt(k) }
            }
            Stats(
                totalMessages = obj.optInt("totalMessages"),
                formalCount = obj.optInt("formalCount"),
                informalCount = obj.optInt("informalCount"),
                emojiOccurrences = obj.optInt("emojiOccurrences"),
                totalLength = obj.optInt("totalLength"),
                topEmoji = emojiMap
            )
        } catch (e: Exception) {
            Stats()
        }
    }

    private fun save(context: Context, key: String, stats: Stats) {
        val obj = JSONObject().apply {
            put("totalMessages", stats.totalMessages)
            put("formalCount", stats.formalCount)
            put("informalCount", stats.informalCount)
            put("emojiOccurrences", stats.emojiOccurrences)
            put("totalLength", stats.totalLength)
            put("topEmoji", JSONObject(stats.topEmoji as Map<*, *>))
        }
        prefs(context).edit().putString(key, obj.toString()).apply()
    }

    private fun applyMessage(stats: Stats, text: String) {
        if (text.isBlank()) return
        stats.totalMessages += 1
        stats.totalLength += text.length

        if (FORMAL_ENDINGS.any { text.contains(it) }) {
            stats.formalCount += 1
        } else {
            stats.informalCount += 1
        }

        EMOJI_REGEX.findAll(text).forEach { match ->
            stats.emojiOccurrences += 1
            val symbol = match.value.take(2) // collapse "ㅋㅋㅋㅋ" -> "ㅋㅋ" etc.
            stats.topEmoji[symbol] = (stats.topEmoji[symbol] ?: 0) + 1
        }
    }

    /** Call for every message bubble the accessibility service extracts. */
    fun recordMessage(context: Context, partnerKey: String?, isMine: Boolean, text: String) {
        val key = if (isMine) KEY_MY_STYLE else PARTNER_PREFIX + (partnerKey ?: "unknown")
        val stats = load(context, key)
        applyMessage(stats, text)
        save(context, key, stats)
    }

    /** Short Korean summary of the user's own voice, for the system prompt. */
    fun summarizeMyStyle(context: Context): String {
        val s = load(context, KEY_MY_STYLE)
        if (s.totalMessages < 5) return "아직 사용자의 말투 데이터가 충분히 쌓이지 않음 (기본 톤으로 작성)"

        val formalRatio = s.formalCount.toFloat() / s.totalMessages
        val avgLen = s.totalLength / s.totalMessages
        val emojiRate = s.emojiOccurrences.toFloat() / s.totalMessages
        val favoriteEmoji = s.topEmoji.entries.sortedByDescending { it.value }.take(3).map { it.key }

        val formalityDesc = if (formalRatio > 0.5) "존댓말을 자주 사용함" else "반말을 자주 사용함"
        val lengthDesc = when {
            avgLen < 12 -> "메시지를 짧게 씀"
            avgLen < 30 -> "메시지 길이는 보통"
            else -> "메시지를 길게 자세히 씀"
        }
        val emojiDesc = when {
            emojiRate < 0.1 -> "이모티콘/이모지는 거의 안 씀"
            emojiRate < 0.4 -> "이모티콘을 가끔 씀 (${favoriteEmoji.joinToString(", ")} 등)"
            else -> "이모티콘을 자주 씀 (${favoriteEmoji.joinToString(", ")} 등)"
        }

        return "$formalityDesc, $lengthDesc, $emojiDesc"
    }

    /** Short Korean summary of closeness with this specific partner, for the system prompt. */
    fun summarizePartnerCloseness(context: Context, partnerKey: String?): String {
        val s = load(context, PARTNER_PREFIX + (partnerKey ?: "unknown"))
        if (s.totalMessages < 5) return "아직 이 상대와의 대화 데이터가 충분하지 않음 (무난한 톤으로 작성, 이모티콘은 최소로)"

        val formalRatio = s.formalCount.toFloat() / s.totalMessages
        val emojiRate = s.emojiOccurrences.toFloat() / s.totalMessages

        return when {
            formalRatio > 0.6 -> "격식 있는 업무 관계로 보임 (존댓말 위주) — 확인·강조가 필요한 문장에는 절제된 이모지(✅🙏👍)를 써도 좋고, 나머지는 짧고 확신 있게"
            emojiRate > 0.35 -> "친한 사이로 보임 (이모티콘을 자주 주고받음) — 이모티콘을 자연스럽게 섞어도 좋음"
            formalRatio < 0.3 -> "편한 반말 사이로 보임 — 이모티콘은 담백하게, 과하지 않게"
            else -> "무난한 사이로 보임 — 이모티콘은 적당히, 과하지 않게"
        }
    }
}
