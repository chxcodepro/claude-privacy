package dev.chx.claudeprivacy

fun isIpv4(value: String): Boolean {
    val parts = value.split('.')
    return parts.size == 4 && parts.all { it.matches(Regex("[0-9]{1,3}")) && (it.length == 1 || !it.startsWith('0')) && it.toInt() <= 255 }
}

fun isPublicIpv4(value: String): Boolean {
    if (!isIpv4(value)) return false
    val parts = value.split('.')
    val (a, b, c) = parts.map(String::toInt)
    return !(a == 0 || a == 10 || a == 127 || a >= 224 ||
        a == 100 && b in 64..127 || a == 169 && b == 254 ||
        a == 172 && b in 16..31 || a == 192 && b == 168 ||
        a == 192 && b == 0 && c in 0..2 || a == 198 && b in 18..19 ||
        a == 198 && b == 51 && c == 100 || a == 203 && b == 0 && c == 113)
}
