package io.github.kyay10.kontinuity

import kotlinx.collections.immutable.*

public class ListBuilder<T>(private val reader: Reader<MutableList<T>>) : MutableList<T> {
  public val list: MutableList<T> get() = reader.value
  public val readOnly: List<T> get() = reader.unsafeValue
  override fun add(element: T): Boolean = list.add(element)
  override fun remove(element: T): Boolean = list.remove(element)
  override fun addAll(elements: Collection<T>): Boolean = list.addAll(elements)
  override fun addAll(index: Int, elements: Collection<T>): Boolean = list.addAll(index, elements)
  override fun removeAll(elements: Collection<T>): Boolean = list.removeAll(elements)
  override fun retainAll(elements: Collection<T>): Boolean = list.retainAll(elements)
  override fun clear() {
    list.clear()
  }

  override fun set(index: Int, element: T): T = list.set(index, element)
  override fun add(index: Int, element: T) {
    list.add(index, element)
  }

  override fun removeAt(index: Int): T = list.removeAt(index)
  override fun listIterator(): MutableListIterator<T> = list.listIterator()
  override fun listIterator(index: Int): MutableListIterator<T> = list.listIterator(index)
  override fun subList(fromIndex: Int, toIndex: Int): MutableList<T> = list.subList(fromIndex, toIndex)
  override val size: Int
    get() = readOnly.size

  override fun isEmpty(): Boolean = readOnly.isEmpty()
  override fun contains(element: T): Boolean = readOnly.contains(element)
  override fun containsAll(elements: Collection<T>): Boolean = readOnly.containsAll(elements)
  override fun get(index: Int): T = readOnly[index]
  override fun indexOf(element: T): Int = readOnly.indexOf(element)
  override fun lastIndexOf(element: T): Int = readOnly.lastIndexOf(element)
  override fun iterator(): MutableIterator<T> = list.iterator()
  override fun toString(): String = readOnly.toString()
  override fun equals(other: Any?): Boolean = readOnly == other
  override fun hashCode(): Int = readOnly.hashCode()
}

public suspend fun <T, R> runListBuilder(body: suspend ListBuilder<T>.() -> R): R =
  runReader(persistentListOf<T>().builder(), { build().builder() }) { body(ListBuilder(this)) }

public suspend fun <T> buildListLocally(body: suspend ListBuilder<T>.() -> Unit): PersistentList<T> =
  runListBuilder {
    body()
    list.toPersistentList()
  }

public class MapBuilder<K, V>(private val reader: Reader<MutableMap<K, V>>) : MutableMap<K, V> {
  public val map: MutableMap<K, V> get() = reader.value
  public val readOnly: Map<K, V> get() = reader.unsafeValue
  override fun clear() {
    map.clear()
  }

  override fun put(key: K, value: V): V? = map.put(key, value)
  override fun putAll(from: Map<out K, V>) {
    map.putAll(from)
  }

  override fun remove(key: K): V? = map.remove(key)
  override val entries: MutableSet<MutableMap.MutableEntry<K, V>>
    get() = map.entries
  override val keys: MutableSet<K>
    get() = map.keys
  override val values: MutableCollection<V>
    get() = map.values

  override fun containsKey(key: K): Boolean = readOnly.containsKey(key)
  override fun containsValue(value: V): Boolean = readOnly.containsValue(value)
  override fun get(key: K): V? = readOnly[key]
  override fun isEmpty(): Boolean = readOnly.isEmpty()
  override val size: Int
    get() = readOnly.size

  override fun equals(other: Any?): Boolean = readOnly == other
  override fun hashCode(): Int = readOnly.hashCode()
  override fun toString(): String = readOnly.toString()
}

public suspend fun <K, V, R> runMapBuilder(body: suspend MapBuilder<K, V>.() -> R): R =
  runReader(persistentHashMapOf<K, V>().builder(), { build().builder() }) { body(MapBuilder(this)) }

public suspend fun <K, V> buildMapLocally(body: suspend MapBuilder<K, V>.() -> Unit): PersistentMap<K, V> =
  runMapBuilder {
    body()
    map.toPersistentHashMap()
  }