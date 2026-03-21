package com.tntlikely.beecount

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Locale
import java.util.UUID

class AutoBillingAccessibilityService : AccessibilityService() {
    companion object {
        private const val TAG = "AutoBillingA11y"
        private const val ALIPAY_PACKAGE = "com.eg.android.AlipayGphone"
        private const val WECHAT_PACKAGE = "com.tencent.mm"
        private const val DUPLICATE_WINDOW_MS = 3500L
        private const val DB_NAME = "beecount.sqlite"
    }

    private data class LedgerOption(val id: Int, val name: String)

    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayView: View? = null
    private var lastTriggerMillis = 0L
    private var lastTriggerAmount = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            eventTypes =
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags =
                flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "请授予悬浮窗权限以使用自动记账功能",
                Toast.LENGTH_LONG
            ).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }
        LoggerPlugin.info(TAG, "无障碍自动记账服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val packageName = event.packageName?.toString() ?: return
        if (packageName != ALIPAY_PACKAGE && packageName != WECHAT_PACKAGE) return

        val screenText = buildScreenText(event, rootInActiveWindow).trim()
        if (screenText.isEmpty() || !screenText.contains("支付成功")) return

        val amount = extractAmount(screenText) ?: return
        if (!shouldHandle(amount)) return

        mainHandler.post {
            if (overlayView != null) return@post
            showOverlay(amount, packageName)
        }
    }

    override fun onInterrupt() {
        dismissOverlay()
    }

    override fun onDestroy() {
        dismissOverlay()
        super.onDestroy()
    }

    private fun shouldHandle(amount: String): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastTriggerMillis < DUPLICATE_WINDOW_MS && amount == lastTriggerAmount) {
            return false
        }
        lastTriggerMillis = now
        lastTriggerAmount = amount
        return true
    }

    private fun buildScreenText(
        event: AccessibilityEvent,
        root: AccessibilityNodeInfo?
    ): String {
        val values = mutableListOf<String>()
        values.addAll(event.text.mapNotNull { it?.toString() })
        if (event.contentDescription != null) {
            values.add(event.contentDescription.toString())
        }
        if (root != null) {
            collectNodeText(root, values, 0)
        }
        return values.joinToString("\n")
    }

    private fun collectNodeText(
        node: AccessibilityNodeInfo,
        values: MutableList<String>,
        depth: Int
    ) {
        if (depth > 10 || values.size > 300) return
        node.text?.toString()?.let {
            if (it.isNotBlank()) values.add(it)
        }
        node.contentDescription?.toString()?.let {
            if (it.isNotBlank()) values.add(it)
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                collectNodeText(child, values, depth + 1)
            }
        }
    }

    private fun extractAmount(content: String): String? {
        val normalized = content.replace(",", "")
        val regexes = listOf(
            Regex("[¥￥]\\s*(\\d+(?:\\.\\d{1,2})?)"),
            Regex("(?:支付|付款|实付|金额)\\D{0,8}(\\d+(?:\\.\\d{1,2})?)"),
            Regex("(\\d+(?:\\.\\d{1,2})?)\\s*元")
        )
        for (regex in regexes) {
            val match = regex.find(normalized) ?: continue
            val value = match.groupValues.getOrNull(1)?.trim().orEmpty()
            if (value.isBlank()) continue
            val amount = value.toDoubleOrNull() ?: continue
            if (amount > 0.0) {
                return String.format(Locale.US, "%.2f", amount)
            }
        }
        return null
    }

    private fun showOverlay(amount: String, sourcePackage: String) {
        dismissOverlay()
        val ledgers = queryLedgers()
        if (ledgers.isEmpty()) {
            Toast.makeText(this, "未找到可用计数项目", Toast.LENGTH_SHORT).show()
            return
        }

        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(42, 36, 42, 36)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 28f
                setColor(Color.parseColor("#FDFDFD"))
                setStroke(2, Color.parseColor("#E8E8E8"))
            }
        }

        val titleView = TextView(this).apply {
            text = "自动记账"
            textSize = 18f
            setTextColor(Color.parseColor("#111111"))
        }
        root.addView(
            titleView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val amountInput = EditText(this).apply {
            hint = "金额"
            setText(amount)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setTextColor(Color.parseColor("#111111"))
            setHintTextColor(Color.parseColor("#999999"))
            textSize = 20f
        }
        root.addView(
            amountInput,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 28
            }
        )

        val ledgerSpinner = Spinner(this)
        val ledgerNames = ledgers.map { it.name }
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            ledgerNames
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        ledgerSpinner.adapter = adapter
        root.addView(
            ledgerSpinner,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 24
            }
        )

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val cancelButton = Button(this).apply {
            text = "取消"
            setOnClickListener {
                dismissOverlay()
            }
        }
        val confirmButton = Button(this).apply {
            text = "确认"
            setOnClickListener {
                val selected = ledgers.getOrNull(ledgerSpinner.selectedItemPosition)
                val amountValue = amountInput.text?.toString()?.trim().orEmpty()
                val amountDouble = amountValue.toDoubleOrNull()
                if (selected == null || amountDouble == null || amountDouble <= 0) {
                    Toast.makeText(this@AutoBillingAccessibilityService, "金额无效", Toast.LENGTH_SHORT)
                        .show()
                    return@setOnClickListener
                }
                val success = insertExpense(selected.id, amountDouble, sourcePackage)
                if (success) {
                    Toast.makeText(this@AutoBillingAccessibilityService, "已保存支出", Toast.LENGTH_SHORT)
                        .show()
                } else {
                    Toast.makeText(this@AutoBillingAccessibilityService, "保存失败", Toast.LENGTH_SHORT)
                        .show()
                }
                dismissOverlay()
            }
        }
        actionRow.addView(cancelButton)
        actionRow.addView(confirmButton)
        root.addView(
            actionRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 30
            }
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 120
        }

        try {
            windowManager.addView(root, params)
            overlayView = root
        } catch (e: Exception) {
            LoggerPlugin.error(TAG, "显示悬浮窗失败: ${e.message}")
        }
    }

    private fun dismissOverlay() {
        val view = overlayView ?: return
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
        } finally {
            overlayView = null
        }
    }

    private fun queryLedgers(): List<LedgerOption> {
        val dbFile = getDatabaseFile()
        if (!dbFile.exists()) return emptyList()
        val list = mutableListOf<LedgerOption>()
        var db: SQLiteDatabase? = null
        var cursor: Cursor? = null
        try {
            db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            cursor = db.rawQuery(
                "SELECT id, name FROM ledgers ORDER BY created_at ASC",
                null
            )
            while (cursor.moveToNext()) {
                val id = cursor.getInt(0)
                val name = cursor.getString(1) ?: "计数项目$id"
                list.add(LedgerOption(id, name))
            }
        } catch (e: Exception) {
            LoggerPlugin.error(TAG, "读取账本失败: ${e.message}")
        } finally {
            cursor?.close()
            db?.close()
        }
        return list
    }

    private fun insertExpense(ledgerId: Int, amount: Double, sourcePackage: String): Boolean {
        val dbFile = getDatabaseFile()
        if (!dbFile.exists()) return false

        var db: SQLiteDatabase? = null
        return try {
            db = SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE
            )
            db.beginTransaction()

            val categoryId = queryFirstInt(
                db,
                "SELECT id FROM categories WHERE kind = ? ORDER BY sort_order ASC, id ASC LIMIT 1",
                arrayOf("expense")
            )
            val ledgerCurrency = queryFirstString(
                db,
                "SELECT currency FROM ledgers WHERE id = ? LIMIT 1",
                arrayOf(ledgerId.toString())
            )
            val accountId = if (ledgerCurrency != null) {
                queryFirstInt(
                    db,
                    "SELECT id FROM accounts WHERE currency = ? ORDER BY sort_order ASC, id ASC LIMIT 1",
                    arrayOf(ledgerCurrency)
                )
            } else {
                null
            } ?: queryFirstInt(
                db,
                "SELECT id FROM accounts WHERE ledger_id = ? ORDER BY sort_order ASC, id ASC LIMIT 1",
                arrayOf(ledgerId.toString())
            )

            val values = ContentValues().apply {
                put("ledger_id", ledgerId)
                put("type", "expense")
                put("amount", amount)
                if (categoryId != null) put("category_id", categoryId)
                if (accountId != null) put("account_id", accountId)
                put("note", sourceName(sourcePackage) + "支付成功自动记账")
                put("sync_id", UUID.randomUUID().toString())
            }
            db.insertOrThrow("transactions", null, values)
            db.setTransactionSuccessful()
            LoggerPlugin.info(TAG, "自动记账写入成功: ledger=$ledgerId amount=$amount")
            true
        } catch (e: Exception) {
            LoggerPlugin.error(TAG, "自动记账写入失败: ${e.message}")
            false
        } finally {
            try {
                db?.endTransaction()
            } catch (_: Exception) {
            }
            db?.close()
        }
    }

    private fun queryFirstInt(
        db: SQLiteDatabase,
        sql: String,
        args: Array<String>
    ): Int? {
        var cursor: Cursor? = null
        return try {
            cursor = db.rawQuery(sql, args)
            if (cursor.moveToFirst()) cursor.getInt(0) else null
        } catch (_: Exception) {
            null
        } finally {
            cursor?.close()
        }
    }

    private fun queryFirstString(
        db: SQLiteDatabase,
        sql: String,
        args: Array<String>
    ): String? {
        var cursor: Cursor? = null
        return try {
            cursor = db.rawQuery(sql, args)
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } catch (_: Exception) {
            null
        } finally {
            cursor?.close()
        }
    }

    private fun sourceName(sourcePackage: String): String {
        return when (sourcePackage) {
            ALIPAY_PACKAGE -> "支付宝"
            WECHAT_PACKAGE -> "微信"
            else -> "支付应用"
        }
    }

    private fun getDatabaseFile(): File {
        val appFlutterDir = File(applicationInfo.dataDir, "app_flutter")
        return File(appFlutterDir, DB_NAME)
    }
}
