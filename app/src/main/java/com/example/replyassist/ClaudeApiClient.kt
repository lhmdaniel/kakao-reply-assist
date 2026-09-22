package com.example.replyassist

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * A single reply candidate plus how direct vs. indirect it is, so the UI
 * can show a visible tag (color-coded) rather than the user having to
 * guess the tone from the text alone.
 */
data class Suggestion(val text: String, val level: DirectnessLevel)

enum class DirectnessLevel(val label: String) {
    DIRECT("직설적"),
    MID("중간"),
    SOFT("완곡함");

    companion object {
        fun fromApiValue(value: String?): DirectnessLevel = when (value?.lowercase()) {
            "direct" -> DIRECT
            "soft" -> SOFT
            else -> MID
        }
    }
}

/**
 * Thin wrapper around the Anthropic Messages API that turns a chunk of
 * recent conversation text into a small list of reply suggestions spanning
 * a directness spectrum (very direct to very indirect/softened), not just
 * a binary accept/decline.
 *
 * The API key never leaves the device except as this request's header;
 * there is no intermediate server.
 */
class ClaudeApiClient(private val apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json".toMediaType()

    /**
     * @param recentMessages recent chat lines, oldest first, formatted like
     *   "상대: 저기 이번 주 토요일에 시간 괜찮아?"
     * @param toneHint optional user-supplied direction, e.g. "조금 더 미안한 느낌으로"
     * @param draftToRefine optional text the user already typed that should be
     *   polished instead of generating fresh suggestions
     */
    fun getSuggestions(
        recentMessages: List<String>,
        toneHint: String? = null,
        draftToRefine: String? = null,
        myStyleSummary: String? = null,
        partnerClosenessSummary: String? = null
    ): List<Suggestion> {
        val systemPrompt = buildSystemPrompt(toneHint, draftToRefine, myStyleSummary, partnerClosenessSummary)
        val userPrompt = buildUserPrompt(recentMessages, draftToRefine)

        val body = JSONObject().apply {
            put("model", "claude-sonnet-4-6")
            put("max_tokens", 500)
            put("system", systemPrompt)
            put("messages", JSONArray().put(
                JSONObject().apply {
                    put("role", "user")
                    put("content", userPrompt)
                }
            ))
        }

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Claude API 호출 실패: ${response.code} ${response.message}")
            }
            val responseBody = response.body?.string() ?: throw IOException("빈 응답")
            return parseSuggestions(responseBody)
        }
    }

    private fun buildSystemPrompt(
        toneHint: String?,
        draftToRefine: String?,
        myStyleSummary: String?,
        partnerClosenessSummary: String?
    ): String {
        val base = """
            너는 사용자가 카카오톡에서 보낼 답장 초안을 제안하는 도우미야.
            아래 규칙을 반드시 지켜:
            - 최근 대화의 맥락과 말투(반말/존댓말, 이모티콘 사용 여부)를 참고해서 자연스러운 한국어 답장을 만들어.
            - 답장 후보를 5개 만들어. 단순히 수락/거절 두 갈래가 아니라, 같은 취지를 "얼마나 직설적으로 vs 완곡하게" 표현하는지의
              스펙트럼을 따라 다양하게 배치해: 아주 직설적인 것부터 아주 완곡하고 부드러운 것까지 고르게 섞어.
              (참고로 의미 자체가 수락/거절/보류로 갈릴 수도 있는데, 그건 자연스러운 흐름에 맡기고, 핵심은 직설-완곡의 표현 정도 다양성이야)
            - 각 후보마다 "level" 값을 다음 중 하나로 매겨: "direct"(직설적, 돌려 말하지 않고 바로 의사를 전달) /
              "mid"(중간, 적당히 완충 표현을 섞음) / "soft"(완곡함, 조심스럽고 부드럽게 돌려 말함).
              5개 후보에 direct/mid/soft가 최소 한 번씩은 포함되도록 분산시켜.
            - 이모티콘/이모지는 아래에 주어지는 "상대와의 친밀도"에 맞게 답장 텍스트 안에 섞어 넣어. 무조건 "격식 있으면 이모지 없음,
              친하면 이모지 많음" 공식이 아니라, 실제로 필요한지를 기준으로 판단해. 격식 있는 업무 관계에서도 확인·강조가 필요한
              문장(예: "확인해주세요", "전달 부탁드립니다")에는 ✅🙏👍 같은 절제된 이모지를 붙이는 게 자연스러울 수 있고, 반대로
              친한 사이라도 담백하게 말하는 게 사용자 스타일에 맞으면 이모지를 넣지 않는 게 나아. 억지로 모든 후보에 이모지를 넣지 마.
            - 아래에 주어지는 "사용자 본인의 평소 말투"를 최대한 반영해서, 사용자가 직접 쓴 것처럼 자연스럽게 만들어.
            - 각 답장은 한두 문장으로 짧게.
            - 반드시 아래 JSON 배열 형식으로만 답해. 다른 텍스트, 설명, 마크다운 코드블록은 절대 포함하지 마:
              [{"text": "답장 내용", "level": "direct"}, {"text": "답장 내용", "level": "mid"}, ...]
        """.trimIndent()

        val myStyleAddition = if (!myStyleSummary.isNullOrBlank()) {
            "\n\n사용자 본인의 평소 말투: $myStyleSummary"
        } else ""

        val closenessAddition = if (!partnerClosenessSummary.isNullOrBlank()) {
            "\n상대와의 친밀도: $partnerClosenessSummary"
        } else ""

        val toneAddition = if (!toneHint.isNullOrBlank()) {
            "\n사용자가 원하는 톤: \"$toneHint\" — 이 방향에 맞으면서도, direct/mid/soft 세 단계가 섞이도록 5개를 만들어."
        } else ""

        val refineAddition = if (!draftToRefine.isNullOrBlank()) {
            "\n사용자가 이미 쓴 초안이 있어. 이 초안의 의미는 유지하면서 자연스럽게 다듬은 버전 5개를, direct/mid/soft 단계가 섞이도록 만들어."
        } else ""

        return base + myStyleAddition + closenessAddition + toneAddition + refineAddition
    }

    private fun buildUserPrompt(recentMessages: List<String>, draftToRefine: String?): String {
        val history = recentMessages.joinToString("\n")
        return if (!draftToRefine.isNullOrBlank()) {
            "최근 대화:\n$history\n\n다듬을 내 초안: \"$draftToRefine\""
        } else {
            "최근 대화:\n$history\n\n이 대화에 이어서 보낼 답장 후보를 만들어줘."
        }
    }

    private fun parseSuggestions(responseBody: String): List<Suggestion> {
        val root = JSONObject(responseBody)
        val contentArray = root.getJSONArray("content")

        // Find the first text block and parse it as a JSON array of {text, level} objects
        var text = ""
        for (i in 0 until contentArray.length()) {
            val block = contentArray.getJSONObject(i)
            if (block.optString("type") == "text") {
                text = block.getString("text").trim()
                break
            }
        }

        val cleaned = text.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

        return try {
            val arr = JSONArray(cleaned)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                Suggestion(
                    text = obj.getString("text"),
                    level = DirectnessLevel.fromApiValue(obj.optString("level"))
                )
            }
        } catch (e: Exception) {
            // Fallback: if the model didn't return the expected shape, surface
            // the raw text as a single mid-level suggestion rather than crashing.
            if (text.isNotBlank()) listOf(Suggestion(text, DirectnessLevel.MID)) else emptyList()
        }
    }
}
