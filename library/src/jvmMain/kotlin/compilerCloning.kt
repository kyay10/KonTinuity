import kotlin.coroutines.Continuation
import java.lang.reflect.Modifier

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

internal actual val Continuation<*>.isCompilerGenerated: Boolean
  get() = baseContClass.isInstance(this) || coroutineOwnerClass.isInstance(this)
internal actual val Continuation<*>.completion: Continuation<*>
  get() = if (baseContClass.isInstance(this)) completionField.get(this) as Continuation<*>
  else delegateField.get(this) as Continuation<*>
private val cache = hashMapOf<Class<*>, Array<java.lang.reflect.Field>>()

private tailrec fun <T> copyDeclaredFields(
  obj: T, copy: T, clazz: Class<out T>
) {
  val fields = cache.getOrPut(clazz) {
    clazz.declaredFields.also { it.forEach { it.isAccessible = true } }
  }
  for (i in fields.indices) {
    val field = fields[i]
    if (Modifier.isStatic(field.modifiers)) continue
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
  val superclass = clazz.superclass
  if (superclass != null && superclass != contClass && superclass != baseContClass)
    copyDeclaredFields(obj, copy, superclass)
}

@Suppress("UNCHECKED_CAST")
internal actual fun <T> Continuation<T>.copy(completion: Continuation<*>): Continuation<T> = when {
  baseContClass.isInstance(this) -> {
    val clazz = javaClass
    val copy = UNSAFE.allocateInstance(clazz) as Continuation<T>
    completionField.set(copy, completion)
    if (contClass.isInstance(this)) {
      contextField.set(copy, completion.context)
      interceptedField.set(copy, null)
    }
    copyDeclaredFields(this, copy, clazz)
    copy
  }

  else -> {
    coroutineOwnerConstructor.newInstance(completion, infoField.get(this)) as Continuation<T>
  }
}