package com.kenhlive.tv.ui

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Tiện ích hiển thị Ngày/Giờ & Lịch Âm - Dương Việt Nam (Hồ Ngọc Đức algorithm).
 * Định dạng:
 * - Giờ: 15:35
 * - Ngày Dương: Thứ Tư, 16/09/2026
 * - Ngày Âm: 06/08 ÂL • Bính Ngọ
 */
object LunarCalendar {

    private val CAN = arrayOf("Giáp", "Ất", "Bính", "Đinh", "Mậu", "Kỷ", "Canh", "Tân", "Nhâm", "Quý")
    private val CHI = arrayOf("Tý", "Sửu", "Dần", "Mão", "Thìn", "Tỵ", "Ngọ", "Mùi", "Thân", "Dậu", "Tuất", "Hợi")
    private val DAYS_OF_WEEK = arrayOf("", "Chủ Nhật", "Thứ Hai", "Thứ Ba", "Thứ Tư", "Thứ Năm", "Thứ Sáu", "Thứ Bảy")

    private val TZ_VN = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")

    data class LunarDate(val day: Int, val month: Int, val year: Int, val isLeap: Boolean)

    fun formatTime(date: Date = Date()): String {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        sdf.timeZone = TZ_VN
        return sdf.format(date)
    }

    fun formatSolarDate(date: Date = Date()): String {
        val cal = Calendar.getInstance(TZ_VN).apply { time = date }
        val dow = DAYS_OF_WEEK[cal.get(Calendar.DAY_OF_WEEK)]
        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        sdf.timeZone = TZ_VN
        return "$dow, ${sdf.format(date)}"
    }

    fun formatLunarDate(date: Date = Date()): String {
        val cal = Calendar.getInstance(TZ_VN).apply { time = date }
        val d = cal.get(Calendar.DAY_OF_MONTH)
        val m = cal.get(Calendar.MONTH) + 1
        val y = cal.get(Calendar.YEAR)

        val lunar = solar2lunar(d, m, y)
        val canChi = getYearCanChi(lunar.year)
        val dayStr = if (lunar.day < 10) "0${lunar.day}" else "${lunar.day}"
        val monthStr = if (lunar.month < 10) "0${lunar.month}" else "${lunar.month}"
        val leapStr = if (lunar.isLeap) " (N)" else ""

        return "$dayStr/$monthStr$leapStr ÂL • $canChi"
    }

    fun getYearCanChi(year: Int): String {
        val can = CAN[(year + 6) % 10]
        val chi = CHI[(year + 8) % 12]
        return "$can $chi"
    }

    private fun jdFromDate(d: Int, m: Int, y: Int): Double {
        var a = (14 - m) / 12
        var y1 = y + 4800 - a
        var m1 = m + 12 * a - 3
        var jd = d + (153 * m1 + 2) / 5 + 365 * y1 + y1 / 4 - y1 / 100 + y1 / 400 - 32045
        return jd.toDouble()
    }

    private fun newMoon(k: Int): Double {
        val t = k / 1236.85
        val t2 = t * t
        val t3 = t2 * t
        var jd1 = 2415020.75933 + 29.53058868 * k + 0.0001178 * t2 - 0.000000155 * t3
        val m = 359.2242 + 29.10535608 * k - 0.0000333 * t2 - 0.00000347 * t3
        val mpr = 306.0253 + 385.81691806 * k + 0.0107306 * t2 + 0.00001236 * t3
        val f = 21.2964 + 390.67050646 * k - 0.0016528 * t2 - 0.00000239 * t3
        val mr = m * PI / 180.0
        val mprR = mpr * PI / 180.0
        val fr = f * PI / 180.0
        var c1 = (0.1734 - 0.000393 * t) * sin(mr) + 0.0021 * sin(2 * mr)
        c1 -= 0.4068 * sin(mprR) + 0.0161 * sin(2 * mprR)
        c1 -= 0.0004 * sin(3 * mprR)
        c1 += 0.0104 * sin(2 * fr) - 0.0051 * sin(mr + mprR)
        c1 -= 0.0074 * sin(mr - mprR) + 0.0004 * sin(2 * fr + mr)
        c1 -= 0.0004 * sin(2 * fr - mr) - 0.0006 * sin(2 * fr + mprR)
        c1 += 0.0010 * sin(2 * fr - mprR) + 0.0005 * sin(mr + 2 * mprR)
        return jd1 + c1
    }

    private fun sunLongitude(jdn: Double): Double {
        val t = (jdn - 2451545.0) / 36525.0
        val t2 = t * t
        val l0 = 280.46645 + 36000.76983 * t + 0.0003032 * t2
        val m = 357.52910 + 35999.05030 * t - 0.0001559 * t2 - 0.00000048 * t * t2
        val mr = m * PI / 180.0
        val c = (1.914600 - 0.004817 * t - 0.000014 * t2) * sin(mr) +
                (0.019993 - 0.000101 * t) * sin(2 * mr) + 0.000290 * sin(3 * mr)
        var l = l0 + c
        l = l % 360.0
        if (l < 0) l += 360.0
        return l * PI / 180.0
    }

    private fun getSunLongitude(dayNumber: Double, timeZone: Double): Int {
        return floor(sunLongitude(dayNumber - 0.5 - timeZone / 24.0) / PI * 6).toInt()
    }

    private fun getLunarMonth11(yy: Int, timeZone: Double): Double {
        var off = jdFromDate(31, 12, yy) - 2415021.07699
        var k = floor(off / 29.530588853).toInt()
        var nm = newMoon(k)
        val sunLong = getSunLongitude(floor(nm + 0.5 + timeZone / 24.0), timeZone)
        if (sunLong >= 9) {
            nm = newMoon(k - 1)
        }
        return floor(nm + 0.5 + timeZone / 24.0)
    }

    fun solar2lunar(dd: Int, mm: Int, yy: Int, timeZone: Double = 7.0): LunarDate {
        val dayNumber = jdFromDate(dd, mm, yy)
        var k = floor((dayNumber - 2415021.07699) / 29.530588853).toInt()
        var monthStart = floor(newMoon(k + 1) + 0.5 + timeZone / 24.0)
        if (monthStart > dayNumber) {
            monthStart = floor(newMoon(k) + 0.5 + timeZone / 24.0)
        }
        var a11 = getLunarMonth11(yy, timeZone)
        var b11 = a11
        var lunarYear = yy
        if (a11 >= monthStart) {
            lunarYear = yy - 1
            a11 = getLunarMonth11(yy - 1, timeZone)
        } else {
            b11 = getLunarMonth11(yy + 1, timeZone)
        }
        val lunarDay = (dayNumber - monthStart + 1).toInt()
        val diff = floor((monthStart - a11) / 29.0).toInt()
        var lunarMonth = diff + 11
        var isLeap = false
        if (b11 - a11 > 365.0) {
            val leapMonthDiff = getLeapMonthOffset(a11, timeZone)
            if (diff >= leapMonthDiff) {
                lunarMonth = diff + 10
                if (diff == leapMonthDiff) {
                    isLeap = true
                }
            }
        }
        if (lunarMonth > 12) {
            lunarMonth -= 12
        }
        if (lunarMonth >= 11 && diff < 4) {
            lunarYear = yy - 1
        }
        return LunarDate(lunarDay, lunarMonth, lunarYear, isLeap)
    }

    private fun getLeapMonthOffset(a11: Double, timeZone: Double): Int {
        var k = floor((a11 - 2415021.07699) / 29.530588853 + 0.5).toInt()
        var last = 0
        var i = 1
        var arc = getSunLongitude(floor(newMoon(k) + 0.5 + timeZone / 24.0), timeZone)
        while (i < 14) {
            last = arc
            k++
            arc = getSunLongitude(floor(newMoon(k) + 0.5 + timeZone / 24.0), timeZone)
            if (arc == last) {
                return i
            }
            i++
        }
        return i
    }
}
