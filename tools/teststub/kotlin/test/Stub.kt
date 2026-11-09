// Local stand-in for kotlin.test, used only to run :core tests in an offline workspace.
package kotlin.test
annotation class Test
fun <T> assertEquals(expected: T, actual: T, message: String? = null) { if (expected != actual) throw AssertionError((message?.let { "$it: " } ?: "") + "expected <$expected> but was <$actual>") }
fun assertTrue(actual: Boolean, message: String? = null) { if (!actual) throw AssertionError(message ?: "expected true") }
fun assertFalse(actual: Boolean, message: String? = null) { if (actual) throw AssertionError(message ?: "expected false") }
fun <T : Any> assertNotNull(actual: T?, message: String? = null): T = actual ?: throw AssertionError(message ?: "expected not null")
fun assertNull(actual: Any?, message: String? = null) { if (actual != null) throw AssertionError((message ?: "expected null") + " but was <$actual>") }
