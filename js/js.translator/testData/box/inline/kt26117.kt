// EXPECTED_REACHABLE_NODES: 1710

// TODO remove stdlib dependency

class A(val list: List<String>) {
    init {
        run {
            list.map { it }
        }
    }

    fun foo() = list.map { it }
}

fun box() = A(listOf("OK")).foo().first()