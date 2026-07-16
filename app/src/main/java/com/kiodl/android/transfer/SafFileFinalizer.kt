package com.kiodl.android.transfer

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

class SafFileFinalizer @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun finalize(
        stagingFile: File,
        destinationTreeUri: String,
        relativePath: String,
    ) = withContext(Dispatchers.IO) {
        val parts = relativePath.split('/').filter(String::isNotBlank)
        require(parts.isNotEmpty()) { "Invalid destination path." }
        var directory = DocumentFile.fromTreeUri(context, Uri.parse(destinationTreeUri))
            ?: throw IOException("저장 폴더에 접근할 수 없습니다.")
        for (name in parts.dropLast(1)) {
            val existing = directory.findFile(name)
            directory = when {
                existing == null -> directory.createDirectory(name)
                existing.isDirectory -> existing
                else -> null
            } ?: throw IOException("폴더를 만들 수 없습니다: $name")
        }

        // Write to a hidden .kdl-part first so a crash mid-copy never leaves a half-written final name.
        // SAF rename is not always supported; fall back to a second full copy into the target name.
        val fileName = parts.last()
        val temporaryName = ".$fileName.kdl-part"
        directory.findFile(temporaryName)?.delete()
        val temporary = directory.createFile("application/octet-stream", temporaryName)
            ?: throw IOException("대상 파일을 만들 수 없습니다: $relativePath")
        copy(stagingFile, temporary)

        val existingTarget = directory.findFile(fileName)
        if (existingTarget != null && !existingTarget.delete()) {
            temporary.delete()
            throw IOException("기존 파일을 교체할 수 없습니다: $relativePath")
        }
        if (!temporary.renameTo(fileName)) {
            val target = directory.createFile("application/octet-stream", fileName)
                ?: throw IOException("대상 파일 이름을 확정할 수 없습니다: $relativePath")
            copy(stagingFile, target)
            temporary.delete()
        }
    }

    private suspend fun copy(source: File, target: DocumentFile) {
        val output = context.contentResolver.openOutputStream(target.uri, "wt")
            ?: throw IOException("대상 파일을 열 수 없습니다: ${target.uri}")
        source.inputStream().use { input ->
            output.use { destination ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    destination.write(buffer, 0, count)
                }
            }
        }
    }
}
