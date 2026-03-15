package io.github.kyay10.kontinuity

import kotlinx.collections.immutable.*

public class ListBuilder<T>(private val reader: Reader<MutableList<T>>) : MutableList<T> {
  public val list: MutableList<T> get() = reader.value
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
    get() = list.size

  override fun isEmpty(): Boolean = list.isEmpty()
  override fun contains(element: T): Boolean = list.contains(element)
  override fun containsAll(elements: Collection<T>): Boolean = list.containsAll(elements)
  override fun get(index: Int): T = list[index]
  override fun indexOf(element: T): Int = list.indexOf(element)
  override fun lastIndexOf(element: T): Int = list.lastIndexOf(element)
  override fun iterator(): MutableIterator<T> = list.iterator()
  override fun toString(): String = list.toString()
  override fun equals(other: Any?): Boolean = list == other
  override fun hashCode(): Int = list.hashCode()
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

  override fun containsKey(key: K): Boolean = map.containsKey(key)
  override fun containsValue(value: V): Boolean = map.containsValue(value)
  override fun get(key: K): V? = map[key]
  override fun isEmpty(): Boolean = map.isEmpty()
  override val size: Int
    get() = map.size

  override fun equals(other: Any?): Boolean = map == other
  override fun hashCode(): Int = map.hashCode()
  override fun toString(): String = map.toString()
}

public suspend fun <K, V, R> runMapBuilderNonPersistent(body: suspend MapBuilder<K, V>.() -> R): R =
  runReader(hashMapOf(), ::HashMap) { body(MapBuilder(this)) }

public suspend fun <K, V, R> runMapBuilder(body: suspend MapBuilder<K, V>.() -> R): R =
  runReader(persistentHashMapOf<K, V>().builder(), { build().builder() }) { body(MapBuilder(this)) }

public suspend fun <K, V> buildMapLocally(body: suspend MapBuilder<K, V>.() -> Unit): PersistentMap<K, V> =
  runMapBuilder {
    body()
    map.toPersistentHashMap()
  }