package com.example.replyassist

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Draws a small floating button over KakaoTalk. Tapping it fetches reply
 * suggestions for the current chat and shows them as a panel of cards,
 * each tagged with how direct vs. indirect it is. The panel also offers
 * two extra actions: asking for a specific tone, and refining whatever
 * the user already typed into the KakaoTalk input field.
 */
class SuggestionOverlayService : Service() {

    companion object {
        const val ACTION_SHOW = "com.example.replyassist.ACTION_SHOW"
        const val ACTION_HIDE = "com.example.replyassist.ACTION_HIDE"
        private const val TAG = "SuggestionOverlay"
    }

    private lateinit var windowManager: WindowManager
    private var floatingButton: View? = null
    private var suggestionPanel: View? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    private var activeJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> showFloatingButton()
            ACTION_HIDE -> hideAll()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        activeJob?.cancel()
        hideAll()
    }

    private fun overlayType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
    }

    // ---------- Floating button ----------

    private fun showFloatingButton() {
        if (floatingButton != null) return

        val button = TextView(this).apply {
            text = "💬"
            textSize = 20f
            setBackgroundColor(Color.parseColor("#3D5A45"))
            setTextColor(Color.WHITE)
            setPadding(28, 20, 28, 20)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = 24
            y = 220
        }

        button.setOnClickListener { requestSuggestions(toneHint = null, draftToRefine = null) }

        try {
            windowManager.addView(button, params)
            floatingButton = button
        } catch (e: Exception) {
            Log.e(TAG, "오버레이 권한이 없어 플로팅 버튼을 띄울 수 없습니다", e)
        }
    }

    // ---------- Fetching suggestions ----------

    private fun requestSuggestions(toneHint: String?, draftToRefine: String?) {
        val service = ReplyAccessibilityService.instance
        if (service == null) {
            Toast.makeText(this, "접근성 서비스가 꺼져 있어요", Toast.LENGTH_SHORT).show()
            return
        }

        val apiKey = SecurePrefs.getApiKey(this)
        if (apiKey.isNullOrBlank()) {
            Toast.makeText(this, "먼저 앱에서 Claude API 키를 입력해주세요", Toast.LENGTH_SHORT).show()
            return
        }

        val recentMessages = service.extractRecentMessages()
        if (recentMessages.isEmpty()) {
            Toast.makeText(this, "대화 내용을 읽어오지 못했어요", Toast.LENGTH_SHORT).show()
            return
        }

        showLoadingPanel()

        val partnerKey = service.getChatPartnerKey()
        val myStyleSummary = StyleProfileStore.summarizeMyStyle(this)
        val partnerSummary = StyleProfileStore.summarizePartnerCloseness(this, partnerKey)

        activeJob?.cancel()
        activeJob = scope.launch {
            val suggestions = try {
                withContext(Dispatchers.IO) {
                    ClaudeApiClient(apiKey).getSuggestions(
                        recentMessages, toneHint, draftToRefine,
                        myStyleSummary, partnerSummary
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "추천 실패", e)
                Toast.makeText(this@SuggestionOverlayService, "답장 추천을 가져오지 못했어요: ${e.message}", Toast.LENGTH_SHORT).show()
                hideSuggestionPanel()
                return@launch
            }
            showSuggestionPanel(suggestions, partnerSummary)
        }
    }

    private fun requestRefine() {
        val service = ReplyAccessibilityService.instance
        val draft = service?.getCurrentDraftText()
        if (draft.isNullOrBlank()) {
            Toast.makeText(this, "먼저 카톡 입력창에 초안을 적어주세요", Toast.LENGTH_SHORT).show()
            return
        }
        requestSuggestions(toneHint = null, draftToRefine = draft)
    }

    // ---------- Panel UI ----------

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun panelLayoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            y = 160
        }
    }

    /** Panel that shows a focusable tone-input row; needs focus to type into it. */
    private fun focusablePanelLayoutParams(): WindowManager.LayoutParams {
        val params = panelLayoutParams()
        params.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        return params
    }

    private fun showLoadingPanel() {
        hideSuggestionPanel()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(20), dp(16), dp(20), dp(16))
            gravity = Gravity.CENTER_VERTICAL
        }
        container.addView(ProgressBar(this))
        val label = TextView(this).apply {
            text = "  답장 후보를 만드는 중..."
            setTextColor(Color.parseColor("#1E1B16"))
        }
        container.addView(label)

        try {
            windowManager.addView(container, panelLayoutParams())
            suggestionPanel = container
        } catch (e: Exception) {
            Log.e(TAG, "로딩 패널을 띄우지 못했습니다", e)
        }
    }

    private fun showSuggestionPanel(suggestions: List<Suggestion>, closenessLabel: String? = null) {
        hideSuggestionPanel()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        // Header row: title + close
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        headerRow.addView(TextView(this).apply {
            text = "답장 추천 (직설적 ↔ 완곡함)"
            setTextColor(Color.parseColor("#6B6355"))
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        headerRow.addView(TextView(this).apply {
            text = "닫기 ✕"
            setTextColor(Color.parseColor("#6B6355"))
            textSize = 12f
            setOnClickListener { hideSuggestionPanel() }
        })
        root.addView(headerRow)

        if (!closenessLabel.isNullOrBlank()) {
            root.addView(TextView(this).apply {
                text = "감지된 관계: $closenessLabel"
                setTextColor(Color.parseColor("#8A6B1E"))
                textSize = 11f
                setPadding(0, dp(2), 0, dp(6))
            })
        }

        // Scrollable list of suggestion cards, each with a color-coded directness tag
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(240)
            )
        }
        val cardsColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        suggestions.forEach { suggestion -> cardsColumn.addView(buildSuggestionCard(suggestion)) }
        scroll.addView(cardsColumn)
        root.addView(scroll)

        // Action row: tone input + apply
        val toneRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        val toneInput = EditText(this).apply {
            hint = "이런 느낌으로: 더 미안하게, 짧게, 장난스럽게…"
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val toneButton = Button(this).apply {
            text = "적용"
            setOnClickListener {
                val hint = toneInput.text.toString().trim()
                if (hint.isNotBlank()) requestSuggestions(toneHint = hint, draftToRefine = null)
            }
        }
        toneRow.addView(toneInput)
        toneRow.addView(toneButton)
        root.addView(toneRow)

        val refineButton = Button(this).apply {
            text = "내가 입력창에 쓴 글 다듬기"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
            setOnClickListener { requestRefine() }
        }
        root.addView(refineButton)

        try {
            // This panel contains an EditText, so it must be able to take focus
            // for the keyboard to appear — unlike the read-only loading panel.
            windowManager.addView(root, focusablePanelLayoutParams())
            suggestionPanel = root
        } catch (e: Exception) {
            Log.e(TAG, "추천 패널을 띄우지 못했습니다", e)
        }
    }

    private fun tagColors(level: DirectnessLevel): Pair<String, String> = when (level) {
        DirectnessLevel.DIRECT -> "#F7E4DE" to "#A8452A" // background, text
        DirectnessLevel.MID -> "#F1EAD3" to "#8A6B1E"
        DirectnessLevel.SOFT -> "#E1EAE0" to "#3D5A45"
    }

    private fun buildSuggestionCard(suggestion: Suggestion): View {
        val (bg, fg) = tagColors(suggestion.level)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F7F5EF"))
                cornerRadius = dp(12).toFloat()
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isClickable = true
        }

        val tag = TextView(this).apply {
            text = "  ${suggestion.level.label}  "
            textSize = 11f
            setTextColor(Color.parseColor(fg))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(bg))
                cornerRadius = dp(20).toFloat()
            }
            setPadding(dp(8), dp(3), dp(8), dp(3))
        }
        val textView = TextView(this).apply {
            text = suggestion.text
            setTextColor(Color.parseColor("#1E1B16"))
            textSize = 14f
            setPadding(0, dp(6), 0, 0)
        }

        card.addView(tag)
        card.addView(textView)
        card.setOnClickListener {
            val filled = ReplyAccessibilityService.instance?.fillInputField(suggestion.text) ?: false
            if (!filled) {
                Toast.makeText(this, "입력창을 찾지 못했어요", Toast.LENGTH_SHORT).show()
            }
            hideSuggestionPanel()
        }
        return card
    }

    private fun hideSuggestionPanel() {
        suggestionPanel?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        suggestionPanel = null
    }

    private fun hideAll() {
        hideSuggestionPanel()
        floatingButton?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        floatingButton = null
    }
}
