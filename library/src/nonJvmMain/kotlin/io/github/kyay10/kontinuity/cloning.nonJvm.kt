package io.github.kyay10.kontinuity

internal actual typealias StackTraceElement = Any
internal actual interface CoroutineStackFrame {
  actual val callerFrame: CoroutineStackFrame?
  actual fun getStackTraceElement(): StackTraceElement?
}