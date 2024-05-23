import kotlin.coroutines.Continuation

private val UNSAFE = Class.forName("sun.misc.Unsafe")
  .getDeclaredField("theUnsafe")
  .apply { isAccessible = true }
  .get(null) as sun.misc.Unsafe

private tailrec fun <T> copyDeclaredFields(obj: T, copy: T, clazz: Class<out T>) {
  for (field in clazz.declaredFields) {
    field.isAccessible = true
    val v = field.get(obj)
    field.set(copy, if (v === obj) copy else v)
  }
  val superclass = clazz.superclass
  if (superclass != null) copyDeclaredFields(obj, copy, superclass)
}

@PublishedApi
internal actual fun <T> Continuation<T>.clone(): Continuation<T> {
  val clazz = javaClass
  val copy = UNSAFE.allocateInstance(clazz) as Continuation<T>
  copyDeclaredFields(this, copy, clazz)
  return copy
}