package com.example.juke.services

/** Seed the selected song, then install its prefix/tail with bounded Binder transactions. */
internal fun <T> installQueueInBatches(items: List<T>, selected: Int, chunkSize: Int = 100,
    seed: (T) -> Unit, insert: (Int, List<T>) -> Unit) {
    require(selected in items.indices)
    require(chunkSize in 1..200)
    seed(items[selected])
    var inserted = 0
    for (chunk in items.take(selected).chunked(chunkSize)) {
        insert(inserted, chunk)
        inserted += chunk.size
    }
    for (chunk in items.drop(selected + 1).chunked(chunkSize)) {
        insert(inserted + 1, chunk)
        inserted += chunk.size
    }
}
