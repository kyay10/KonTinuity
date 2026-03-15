package io.github.kyay10.kontinuity

import sun.misc.Unsafe
import java.lang.reflect.Modifier
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.jvm.internal.CloningUtils
import kotlin.coroutines.jvm.internal.CoroutineStackFrame

public interface MultishotContinuation<T> : Continuation<T> {
  public fun copy(completion: Continuation<*>, context: CoroutineContext): Continuation<T>
}

public actual typealias StackTraceElement = java.lang.StackTraceElement

public actual typealias CoroutineStackFrame = CoroutineStackFrame

internal actual const val SUPPORTS_MULTISHOT = true

internal actual val Continuation<*>.completion: Continuation<*>?
  get() = CloningUtils.getParentContinuation(this)

private val UNSAFE = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as Unsafe

private val cache = hashMapOf<Class<*>, Array<java.lang.reflect.Field>>()

private tailrec fun <T> copyDeclaredFields(
  obj: T, copy: T, clazz: Class<out T>
) {
  val fields = cache.getOrPut(clazz) {
    clazz.declaredFields.also { fields -> fields.forEach { it.isAccessible = true } }
  }
  for (i in fields.indices) {
    val field = fields[i]
    if (Modifier.isStatic(field.modifiers)) continue
    // TODO check if optimizing based on type is useful
    when (field.type) {
      Int::class.java -> field.setInt(copy, field.getInt(obj))
      else -> {
        val v = field.get(obj)
        // Sometimes generated continuations contain references to themselves
        // hence we need to change that immediate reference (or else we run into memory leaks)
        field.set(copy, if (v === obj) copy else v)
      }
    }
  }
  if (CloningUtils.isContinuationBaseClass(clazz)) return
  copyDeclaredFields(obj, copy, clazz.superclass ?: return)
}

internal actual fun <T> Continuation<T>.invokeCopied(
  completion: Continuation<*>,
  context: CoroutineContext,
  result: Result<T>,
): Any? {
  @Suppress("UNCHECKED_CAST")
  val copy =
    if (this is MultishotContinuation) this.copy(completion, context)
    else (UNSAFE.allocateInstance(javaClass) as Continuation<T>).apply {
      CloningUtils.initialize(this, completion, context)
      copyDeclaredFields(this@invokeCopied, this, javaClass)
    }
  return CloningUtils.invokeSuspend(copy, result.getOrElse { CloningUtils.createFailure(it) })
}