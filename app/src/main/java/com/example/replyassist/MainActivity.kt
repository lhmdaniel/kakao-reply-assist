package com.example.replyassist

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val apiKeyInput = findViewById<EditText>(R.id.apiKeyInput)
        val saveKeyButton = findViewById<Button>(R.id.saveKeyButton)
        val openAccessibilityButton = findViewById<Button>(R.id.openAccessibilityButton)
        val openOverlayButton = findViewById<Button>(R.id.openOverlayButton)

        SecurePrefs.getApiKey(this)?.let {
            apiKeyInput.setText(it)
        }

        saveKeyButton.setOnClickListener {
            val key = apiKeyInput.text.toString().trim()
            if (key.isBlank()) {
                Toast.makeText(this, "API 키를 입력해주세요", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            SecurePrefs.saveApiKey(this, key)
            Toast.makeText(this, "저장했어요", Toast.LENGTH_SHORT).show()
        }

        openAccessibilityButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        openOverlayButton.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } else {
                Toast.makeText(this, "이 안드로이드 버전에서는 필요 없어요", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
