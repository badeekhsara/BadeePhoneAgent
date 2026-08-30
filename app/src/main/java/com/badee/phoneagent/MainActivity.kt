package com.badee.phoneagent

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.badee.phoneagent.agent.BadeeAccessibilityService
import com.badee.phoneagent.audit.AuditLog
import com.badee.phoneagent.security.AuthTokenStore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : Activity() {
    private lateinit var permissionStatus: TextView
    private lateinit var serverStatus: TextView
    private lateinit var auditText: TextView
    private lateinit var tokenStore: AuthTokenStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tokenStore = AuthTokenStore(this)
        tokenStore.getOrCreate()
        setContentView(buildContent())
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun buildContent(): View {
        val padding = dp(20)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(padding, dp(28), padding, dp(36))
            setBackgroundColor(getColor(R.color.agent_background))
        }

        content.addView(text(R.string.dashboard_title, 30f, Typeface.BOLD, R.color.agent_text))
        content.addView(text(R.string.dashboard_subtitle, 16f, Typeface.NORMAL, R.color.agent_muted).withBottom(24))

        val permissionCard = card()
        permissionCard.addView(text(R.string.permission_title, 19f, Typeface.BOLD, R.color.agent_text))
        permissionStatus = text("", 15f, Typeface.NORMAL, R.color.agent_muted).withTop(8)
        permissionCard.addView(permissionStatus)
        permissionCard.addView(button(R.string.open_accessibility) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }.withTop(14))
        content.addView(permissionCard)

        val serverCard = card().withTop(14)
        serverCard.addView(text(R.string.server_title, 19f, Typeface.BOLD, R.color.agent_text))
        serverStatus = text("", 15f, Typeface.NORMAL, R.color.agent_muted).withTop(8)
        serverCard.addView(serverStatus)
        serverCard.addView(button(R.string.copy_token, ::copyToken).withTop(14))
        serverCard.addView(button(R.string.rotate_token, ::rotateToken).withTop(8))
        serverCard.addView(text(R.string.security_note, 14f, Typeface.NORMAL, R.color.agent_muted).withTop(14))
        content.addView(serverCard)

        content.addView(button(R.string.refresh_status, ::refresh).withTop(16))
        content.addView(text(R.string.audit_title, 20f, Typeface.BOLD, R.color.agent_text).withTop(28))
        auditText = text("", 14f, Typeface.NORMAL, R.color.agent_text).withTop(10)
        content.addView(card().withTop(8).apply { addView(auditText) })

        return ScrollView(this).apply { addView(content) }
    }

    private fun refresh() {
        val enabled = isAccessibilityServiceEnabled()
        permissionStatus.setText(if (enabled) R.string.permission_enabled else R.string.permission_disabled)
        permissionStatus.setTextColor(getColor(if (enabled) R.color.agent_success else R.color.agent_warning))

        if (BadeeAccessibilityService.isRunning) {
            serverStatus.text = getString(R.string.server_running, BuildConfig.COMMAND_PORT)
            serverStatus.setTextColor(getColor(R.color.agent_success))
        } else {
            serverStatus.setText(R.string.server_stopped)
            serverStatus.setTextColor(getColor(R.color.agent_warning))
        }

        val formatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
        val entries = AuditLog(this).latest()
        auditText.text = if (entries.isEmpty()) {
            getString(R.string.audit_empty)
        } else {
            entries.joinToString("\n\n") { entry ->
                val icon = if (entry.success) "✓" else "!"
                val time = runCatching { formatter.format(Instant.parse(entry.timestamp)) }.getOrDefault("—")
                "$icon  $time  ${entry.action}\n${entry.detail}"
            }
        }
    }

    private fun copyToken() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Badee Phone Agent token", tokenStore.getOrCreate()))
        Toast.makeText(this, R.string.token_copied, Toast.LENGTH_SHORT).show()
    }

    private fun rotateToken() {
        tokenStore.rotate()
        Toast.makeText(this, R.string.token_rotated, Toast.LENGTH_LONG).show()
        refresh()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = ComponentName(this, BadeeAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        gravity = Gravity.START
        setPadding(dp(18), dp(18), dp(18), dp(18))
        setBackgroundColor(Color.WHITE)
        elevation = dp(2).toFloat()
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun text(resource: Int, size: Float, style: Int, color: Int): TextView =
        text(getString(resource), size, style, color)

    private fun text(value: String, size: Float, style: Int, color: Int): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(getColor(color))
        setTypeface(typeface, style)
        gravity = Gravity.START
        layoutDirection = View.LAYOUT_DIRECTION_RTL
    }

    private fun button(resource: Int, onClick: () -> Unit): Button = Button(this).apply {
        setText(resource)
        isAllCaps = false
        setTextColor(Color.WHITE)
        setBackgroundColor(getColor(R.color.agent_primary))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun <T : View> T.withTop(value: Int): T = apply {
        val parameters = (layoutParams as? LinearLayout.LayoutParams)
            ?: LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        parameters.topMargin = dp(value)
        layoutParams = parameters
    }

    private fun <T : View> T.withBottom(value: Int): T = apply {
        val parameters = (layoutParams as? LinearLayout.LayoutParams)
            ?: LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        parameters.bottomMargin = dp(value)
        layoutParams = parameters
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
