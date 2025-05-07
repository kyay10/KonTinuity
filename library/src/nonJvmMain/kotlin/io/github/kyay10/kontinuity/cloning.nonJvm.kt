package io.github.kyay10.kontinuity

public actual typealias StackTraceElement = Any
public actual interface CoroutineStackFrame {
  public actual val callerFrame: CoroutineStackFrame?
  public actual fun getStackTraceElement(): StackTraceElement?
}