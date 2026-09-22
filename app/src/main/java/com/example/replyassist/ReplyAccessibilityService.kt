package com.example.replyassist

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Watches KakaoTalk chat windows, extracts the last handful of visible
 * message lines, and can push text into the chat's input field.
 *
 * IMPORTANT: KakaoTalk's internal view structure is not public and changes
 * between app updates. The heuristics below (looking for TextView-like nodes
 * inside the message list, and an EditText-like node near the bottom of the
 * screen) are a reasonable starting point, but you will likely need to
 * inspect the real view hierarchy with Android Studio's Layout Inspector
 * (or `adb shell uiautomator dump`) on your installed KakaoTalk version and
 * adjust the matching logic accordingly.
 */
class ReplyAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ReplyAccessibility"
        private const val KAKAO_PACKAGE = "com.kakao.talk"
        private const val MAX_RECENT_MESSAGES = 12

        // Set by MainActivity/SuggestionOverlayService via a bound reference
        // in a real app you'd likely use a proper Binder or a small event bus;
        // kept simple here for clarity.
        var instance: ReplyAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.packageName != KAKAO_PACKAGE) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (isLikelyChatRoom()) {
                    startOverlayIfNeeded()
                } else {
                    stopOverlay()
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.i(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    /** Very rough heuristic: a chat room screen usually has an editable input field. */
    private fun isLikelyChatRoom(): Boolean {
        return findInputField() != null
    }

    private fun startOverlayIfNeeded() {
        val intent = Intent(this, SuggestionOverlayService::class.java)
        intent.action = SuggestionOverlayService.ACTION_SHOW
        startService(intent)
    }

    private fun stopOverlay() {
        val intent = Intent(this, SuggestionOverlayService::class.java)
        intent.action = SuggestionOverlayService.ACTION_HIDE
        startService(intent)
    }

    /** One extracted chat bubble, tagged as mine or the other person's. */
    private data class ChatLine(val isMine: Boolean, val text: String)

    /**
     * Walks the active window looking for text nodes that look like chat
     * bubbles, tags each as mine vs. the other person's using screen
     * position (KakaoTalk right-aligns your own bubbles, like most
     * messengers), records them into StyleProfileStore for learning, and
     * returns the last [MAX_RECENT_MESSAGES] formatted for the prompt.
     */
    fun extractRecentMessages(): List<String> {
        val root = rootInActiveWindow ?: return emptyList()
        val lines = mutableListOf<ChatLine>()
        collectChatLines(root, lines)
        val recent = lines.takeLast(MAX_RECENT_MESSAGES)

        val partnerKey = getChatPartnerKey()
        recent.forEach { line -> StyleProfileStore.recordMessage(this, partnerKey, line.isMine, line.text) }

        return recent.map { if (it.isMine) "나: ${it.text}" else "상대: ${it.text}" }
    }

    private fun collectChatLines(node: AccessibilityNodeInfo, out: MutableList<ChatLine>) {
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrBlank() && node.className?.contains("TextView") == true && text.length > 1) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val screenWidth = resources.displayMetrics.widthPixels
            // Heuristic: bubbles anchored past the horizontal midpoint are
            // usually mine (right-aligned). If your KakaoTalk theme mirrors
            // this (e.g. RTL layout), flip the comparison.
            val isMine = bounds.left > screenWidth / 2
            out.add(ChatLine(isMine, text))
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectChatLines(child, out)
            child.recycle()
        }
    }

    /**
     * Rough guess at who this chat room is with, used only as a local key to
     * bucket closeness stats per partner — never sent anywhere. Looks for a
     * short text node near the very top of the screen (the room title in the
     * action bar). Falls back to null (a shared "unknown" bucket) if nothing
     * matches; adjust with Layout Inspector if your KakaoTalk version differs.
     */
    fun getChatPartnerKey(): String? {
        val root = rootInActiveWindow ?: return null
        val screenHeight = resources.displayMetrics.heightPixels
        var found: String? = null

        fun search(node: AccessibilityNodeInfo) {
            if (found != null) return
            val text = node.text?.toString()?.trim()
            if (!text.isNullOrBlank() && node.className?.contains("TextView") == true) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (bounds.top < screenHeight / 10 && text.length in 1..20) {
                    found = text
                    return
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                search(child)
                child.recycle()
                if (found != null) return
            }
        }
        search(root)
        return found
    }

    private fun findInputField(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return findFirstEditText(root)
    }

    private fun findFirstEditText(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.className?.contains("EditText") == true) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findFirstEditText(child)
            if (result != null) return result
            child.recycle()
        }
        return null
    }

    /**
     * Reads whatever the user has already typed into the KakaoTalk input
     * field, so it can be sent to Claude for refining instead of generating
     * suggestions from scratch.
     */
    fun getCurrentDraftText(): String? {
        val field = findInputField() ?: return null
        return field.text?.toString()
    }

    /**
     * Fills the chat's input field with [text]. Does NOT send it — the user
     * still has to tap KakaoTalk's own send button.
     */
    fun fillInputField(text: String): Boolean {
        val field = findInputField() ?: return false
        val arguments = Bundle()
        arguments.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            text
        )
        return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }
}
