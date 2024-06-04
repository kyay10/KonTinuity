import kotlin.coroutines.Continuation

private val UNSAFE = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }
  .get(null) as sun.misc.Unsafe

private val baseContClass = Class.forName("kotlin.coroutines.jvm.internal.BaseContinuationImpl")
private val contClass = Class.forName("kotlin.coroutines.jvm.internal.ContinuationImpl")
private val completionField = baseContClass.getDeclaredField("completion").apply { isAccessible = true }
private val interceptedField = contClass.getDeclaredField("intercepted").apply { isAccessible = true }
private val contextField = contClass.getDeclaredField("_context").apply { isAccessible = true }
private val coroutineOwnerClass = Class.forName("kotlinx.coroutines.debug.internal.DebugProbesImpl\$CoroutineOwner")
private val coroutineOwnerConstructor = coroutineOwnerClass.declaredConstructors.first().apply { isAccessible = true }
private val delegateField = coroutineOwnerClass.getDeclaredField("delegate").apply { isAccessible = true }
private val infoField = coroutineOwnerClass.getDeclaredField("info").apply { isAccessible = true }

private tailrec fun <T> copyDeclaredFields(
  obj: T, copy: T, clazz: Class<out T>
) {
  for (field in clazz.declaredFields) {
    field.isAccessible = true
    val v = field.get(obj)
    field.set(copy, if (v === obj) copy else v)
  }
  val superclass = clazz.superclass
  if (superclass != null && superclass != contClass) copyDeclaredFields(obj, copy, superclass)
}

@Suppress("UNCHECKED_CAST")
internal actual fun <T, R> Continuation<T>.compilerGeneratedCloneOrNull(
  prompt: Prompt<R>, replacement: Hole<R>
): Continuation<T>? = if (baseContClass.isInstance(this)) {
  val completion = completionField.get(this) as Continuation<*>
  val newCompletion = if (completion === this) completion else completion.clone(prompt, replacement)
  val clazz = javaClass
  val copy = UNSAFE.allocateInstance(clazz) as Continuation<T>
  completionField.set(copy, newCompletion)
  if (contClass.isInstance(this)) {
    contextField.set(copy, newCompletion.context)
    interceptedField.set(copy, null)
  }
  copyDeclaredFields(this, copy, clazz)
  copy
} else if (coroutineOwnerClass.isInstance(this)) {
  val delegate = delegateField.get(this) as Continuation<*>
  coroutineOwnerConstructor.newInstance(delegate.clone(prompt, replacement), infoField.get(this)) as Continuation<T>
} else null