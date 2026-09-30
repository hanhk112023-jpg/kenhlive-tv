package com.kenhlive.tv

import android.content.Context

/**
 * Yêu thích (v7) — lưu SharedPreferences (nhẹ RAM, không thêm thư viện).
 * Hiện dùng cho kênh IPTV; khóa là IptvChannel.id.
 */
object Favorites {
    private const val PREF = "favorites"
    private const val KEY_IPTV = "iptv"

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun iptvIds(ctx: Context): Set<String> =
        HashSet(sp(ctx).getStringSet(KEY_IPTV, emptySet()) ?: emptySet())

    fun isIptv(ctx: Context, id: String): Boolean = id in iptvIds(ctx)

    /** Đảo trạng thái, trả về trạng thái MỚI (true = đã thích). */
    fun toggleIptv(ctx: Context, id: String): Boolean {
        val set = iptvIds(ctx)
        val now = if (id in set) { set.remove(id); false } else { set.add(id); true }
        sp(ctx).edit().putStringSet(KEY_IPTV, set).apply()
        return now
    }
}
