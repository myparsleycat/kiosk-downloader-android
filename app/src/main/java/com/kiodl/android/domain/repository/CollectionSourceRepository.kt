package com.kiodl.android.domain.repository

import com.kiodl.android.domain.model.CollectionProbe
import com.kiodl.android.domain.model.LoadedCollection
import com.kiodl.android.domain.model.CollectionNode

interface CollectionSourceRepository {
    suspend fun probe(url: String): CollectionProbe

    suspend fun load(url: String, password: String? = null): LoadedCollection

    suspend fun listZipEntries(collection: LoadedCollection, fileId: String): List<CollectionNode>

    suspend fun verifyZipPassword(collection: LoadedCollection, fileId: String, password: String)
}

class CollectionPasswordRequiredException : Exception("Collection is password-protected.")

class CollectionInvalidPasswordException : Exception("Invalid password.")
