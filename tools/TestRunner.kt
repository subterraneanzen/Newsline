import kotlin.test.Test
fun main(args: Array<String>) {
    var pass = 0; var fail = 0
    for (cn in args) {
        val c = Class.forName(cn)
        for (m in c.declaredMethods.filter { it.isAnnotationPresent(Test::class.java) }.sortedBy { it.name }) {
            try { m.invoke(c.getDeclaredConstructor().newInstance()); pass++; println("  ok   ${c.simpleName}.${m.name}") }
            catch (e: java.lang.reflect.InvocationTargetException) { fail++; println("  FAIL ${c.simpleName}.${m.name}: ${e.targetException}"); }
        }
    }
    println("passed=$pass failed=$fail"); if (fail > 0) kotlin.system.exitProcess(1)
}
