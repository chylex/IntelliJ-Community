// "Wrap with '?.let { ... }' call" "true"
// WITH_STDLIB

fun Int.foo(x: Int) = this + x

val arg: Int? = 42

val res = 24.hashCode().foo(<caret>arg) + 1

// FUS_K2_QUICKFIX_NAME: org.jetbrains.kotlin.idea.quickfix.fixes.WrapWithSafeLetCallFixFactories$applicator$1