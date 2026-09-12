package com.kenhlive.tv

import java.text.Normalizer

/** Chuẩn hoá text tìm kiếm: bỏ dấu tiếng Việt + lowercase ("real" khớp "Real", "chuc" khớp "Chúc"). */
object TextNorm {
    private val MARKS = "\\p{Mn}+".toRegex()

    fun norm(s: String): String {
        val n = Normalizer.normalize(s, Normalizer.Form.NFD).replace(MARKS, "")
        return n.lowercase().replace("đ", "d")
    }
}
