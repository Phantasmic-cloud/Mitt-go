package com.phantasmic.mitt.go

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Deliberately native and free of Compose/Miuix so a Compose startup failure
 * can still be displayed and shared.
 */
class CrashActivity : Activity() {
    companion object {
        const val EXTRA_REPORT = "report"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val report = intent.getStringExtra(EXTRA_REPORT)
            ?: runCatching { java.io.File(filesDir, "last-crash.txt").readText() }.getOrNull()
            ?: "没有可读取的崩溃信息"

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(
            TextView(this).apply {
                text = "Mitt-go 发生错误"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            },
        )
        root.addView(
            TextView(this).apply {
                text = "把下面的日志分享给开发者即可定位。"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, pad / 2, 0, pad / 2)
            },
        )

        val scroll = ScrollView(this)
        scroll.addView(
            TextView(this).apply {
                text = report
                setTextIsSelectable(true)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
            },
        )
        root.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, pad, 0, 0)
        }
        actions.addView(
            Button(this).apply {
                text = "分享日志"
                setOnClickListener {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "Mitt-go crash report")
                        putExtra(Intent.EXTRA_TEXT, report)
                    }
                    startActivity(Intent.createChooser(send, "分享崩溃日志"))
                }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        actions.addView(
            Button(this).apply {
                text = "关闭"
                setOnClickListener { finish() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        root.addView(actions)

        setContentView(root)
    }
}