package io.github.kyay10.kontinuity

import sun.misc.Unsafe
import java.lang.reflect.Modifier
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.jvm.internal.CloningUtils
import kotlin.coroutines.jvm.internal.CoroutineStackFrame

// TODO make subclasses of BaseContinuationImpl instead so that `is` and `copy` are faster
@PublishedApi
internal interface MultishotContinuation<T> : Continuation<T> {
  fun copy(completion: Continuation<*>, context: CoroutineContext): Continuation<T>
}

internal actual typealias StackTraceElement = java.lang.StackTraceElement

internal actual typealias CoroutineStackFrame = CoroutineStackFrame

@Suppress("UNCHECKED_CAST")
internal actual val <N> Frames<*, N>.completion: Stack<N>?
  get() = CloningUtils.getParentContinuation(frames)?.let { Stack(it as Continuation<N>) }

private val UNSAFE = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as Unsafe

private val cache = hashMapOf<Class<*>, Array<java.lang.reflect.Field>>()

private tailrec fun <T> copyDeclaredFields(
  from: T, to: T, clazz: Class<out T>
) {
  val fields = cache.getOrPut(clazz) {
    clazz.declaredFields.also { fields -> fields.forEach { it.isAccessible = true } }
  }
  for (i in fields.indices) {
    val field = fields[i]
    if (Modifier.isStatic(field.modifiers)) continue
    // TODO check if optimizing based on type is useful
    when (field.type) {
      Int::class.java -> field.setInt(to, field.getInt(from))
      else -> {
        val v = field.get(from)
        // Sometimes generated continuations contain references to themselves
        // hence we need to change that immediate reference (or else we run into memory leaks)
        field.set(to, if (v === from) to else v)
      }
    }
  }
  if (CloningUtils.isContinuationBaseClass(clazz)) return
  copyDeclaredFields(from, to, clazz.superclass ?: return)
}

@Suppress("UNCHECKED_CAST")
private fun <S, N> Frames<S, N>.copy(completion: Stack<N>, context: SplitCont<*>): Continuation<S> =
  if (frames is MultishotContinuation) frames.copy(completion.frames, context)
  else (UNSAFE.allocateInstance(javaClass) as Continuation<S>).apply {
    copyDeclaredFields(frames, this, javaClass)
    CloningUtils.initialize(this, completion.frames, context)
  }

@Suppress("UNCHECKED_CAST")
internal actual fun <S, N> Frames<S, N>.invokeCopied(
  completion: Stack<N>,
  context: SplitCont<*>,
  result: Result<S>
): N = CloningUtils.invokeSuspend(copy(completion, context), result.getOrElse { CloningUtils.createFailure(it) }) as N