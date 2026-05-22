package io.github.kyay10.kontinuity.stacks

import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine

internal const val SEGMENT_ALREADY_USED = "Segment was already used once, but is being reused again"

@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
@DslMarker
public annotation class StacksDsl

public class SubCont internal constructor(internal val handler: Handler, internal val stack: Stack) {
  internal var used = false

  @StacksDsl
  public suspend infix fun locally(value: suspend () -> Nothing): Nothing = suspendCoroutineToTrampoline {
    if (used) error(SEGMENT_ALREADY_USED)
    used = true
    handler.stack = it
    handler.trampoline = this
    value.startCoroutineIntercepted(stack)
  }
}

public class Handler : Continuation<Nothing> {
  internal var stack: Stack? = null
  internal lateinit var trampoline: Trampoline
  override val context: CoroutineContext
    get() = trampoline

  override fun resumeWith(result: Result<Nothing>): Unit =
    with(trampoline) { stack!!.resumeIntercepted(result.fold({ it }, { it })) }
}

@StacksDsl
public suspend fun yieldToTrampoline(): Unit = suspendCoroutineUninterceptedOrReturn {
  with(it.context as Trampoline) { it.resumeIntercepted() }
  COROUTINE_SUSPENDED
}

@StacksDsl
public suspend fun Handler.use(body: suspend (SubCont) -> Nothing): Nothing = suspendCoroutineToTrampoline {
  body.startCoroutineIntercepted(SubCont(this@use, it), stack!!)
}

@Suppress("SuspendCoroutineLacksCancellationGuarantees")
public suspend fun <R> runCC(body: suspend () -> R): R = suspendCoroutine { c ->
  body.startCoroutine(Continuation(Trampoline(c.context), c::resumeWith))
}

internal suspend inline fun suspendCoroutineToTrampoline(crossinline block: Trampoline.(Stack) -> Unit): Nothing =
  collectStack { stack ->
    block(stack)
    COROUTINE_SUSPENDED
  }

internal suspend inline fun collectStack(crossinline block: Trampoline.(Stack) -> Any?): Nothing =
  suspendCoroutineUninterceptedOrReturn {
    (it.context as Trampoline).block(Stack(it))
  }
