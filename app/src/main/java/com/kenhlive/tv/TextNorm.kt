package com.kenhlive.tv

import java.text.Normalizer
import java.util.Locale

/** Chuẩn hoá text tìm kiếm: bỏ dấu tiếng Việt + lowercase ("real" khớp "Real", "chuc" khớp "Chúc"). */
object TextNorm {
    private val MARKS = "\\p{Mn}+".toRegex()

    fun norm(s: String): String {
        val n = Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(MARKS, "")
            .replace("đ", "d")
        return n.trim()
    }
}
