// EXPECTED_REACHABLE_NODES: 1381

package foo

fun box(): String {
    val map = mapOf(1 to "")
    val i = 1
    return when (i) {
        in map -> "OK"
        else -> "fail"
    }
}