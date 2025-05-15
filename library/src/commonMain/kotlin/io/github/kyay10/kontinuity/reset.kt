package io.github.kyay10.kontinuity

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine

@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
@DslMarker
public annotation class ResetDsl

@PublishedApi
internal enum class OnInit {
  NONE,
  REUSABLE,
  COPY,
  REPUSH,
}

public class SubCont<in T, out R> @PublishedApi internal constructor(
  private var init: SingleUseSegment<T, R>,
  private var onInitialize: OnInit = OnInit.NONE,
) {
  @PublishedApi
  internal fun composedWith(stack: SplitSeq<R>): SplitSeq<T> {
    when (onInitialize) {
      OnInit.REUSABLE -> init = init.makeReusable()
      OnInit.COPY -> init = init.makeCopy()
      OnInit.REPUSH -> init.makeReusable()
      OnInit.NONE -> Unit
    }
    onInitialize = OnInit.NONE
    return init prependTo stack
  }
}

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
internal expect open class NoTrace() : CancellationException
internal data object SuspendedException : NoTrace()

public suspend fun <R> runCC(body: suspend MultishotScope.() -> R): R {
  val trampoline = coroutineContext.makeTrampoline()
  return withContext(trampoline) {
    suspendCoroutine {
      val cont = EmptyCont(it, trampoline)
      body.startCoroutine(cont, cont)
    }
  }
}