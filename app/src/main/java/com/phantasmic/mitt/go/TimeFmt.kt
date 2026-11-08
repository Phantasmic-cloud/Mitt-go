package com.phantasmic.mitt.go

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全 App 唯一的时间格式化入口。
 *
 * `SimpleDateFormat` 不是线程安全的，这里每个格式一份 `ThreadLocal`。
 * 新加时间显示请用这里的函数，不要再在页面里 `new SimpleDateFormat`。
 */
object TimeFmt {
    private val clockFormat = threadLocal("HH:mm:ss", Locale.US)
    private val clockMillisFormat = threadLocal("HH:mm:ss.SSS", Locale.US)
    private val dayFormat = threadLocal("MM-dd", Locale.US)
    private val dayClockFormat = threadLocal("MM-dd HH:mm", Locale.US)
    private val clockShortFormat = threadLocal("HH:mm", Locale.US)
    private val stampFormat = threadLocal("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val stampMillisFormat = threadLocal("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val stampDefaultFormat = threadLocal("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val fileStampFormat = threadLocal("yyyyMMdd_HHmmss", Locale.US)

    private fun threadLocal(pattern: String, locale: Locale) =
        object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue() = SimpleDateFormat(pattern, locale)
        }

    /** `12:34:56` */
    fun clock(millis: Long): String = clockFormat.get()!!.format(Date(millis))

    /** `12:34:56.789` */
    fun clockMillis(millis: Long): String = clockMillisFormat.get()!!.format(Date(millis))

    /** `11-01` */
    fun day(millis: Long): String = dayFormat.get()!!.format(Date(millis))

    /** `11-01 12:30` */
    fun dayClock(millis: Long): String = dayClockFormat.get()!!.format(Date(millis))

    /** `12:30` */
    fun clockShort(millis: Long): String = clockShortFormat.get()!!.format(Date(millis))

    /** `2026-11-08 12:30:00` */
    fun stamp(millis: Long): String = stampFormat.get()!!.format(Date(millis))

    /** `2026-11-08 12:30:00.123` */
    fun stampMillis(millis: Long): String = stampMillisFormat.get()!!.format(Date(millis))

    /** 同上，但用系统默认 locale（崩溃报告用）。 */
    fun stampDefault(millis: Long): String = stampDefaultFormat.get()!!.format(Date(millis))

    /** 文件名里的 `20261108_123000` */
    fun fileStamp(millis: Long = System.currentTimeMillis()): String =
        fileStampFormat.get()!!.format(Date(millis))
}
