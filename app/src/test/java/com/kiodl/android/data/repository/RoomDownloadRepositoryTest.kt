package com.kiodl.android.data.repository

import com.kiodl.android.domain.model.DirectoryNode
import com.kiodl.android.domain.model.FileNode
import com.kiodl.android.domain.model.ZipNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomDownloadRepositoryTest {
    @Test
    fun flattensTreeToDesktopCompatibleRelativePaths() {
        val tree = DirectoryNode(
            id = "root",
            name = "",
            entries = listOf(
                DirectoryNode(
                    id = "dir",
                    name = "folder",
                    entries = listOf(FileNode("file", "photo.jpg", 10)),
                ),
                ZipNode("zip", "archive.zip", 20),
            ),
        )

        val files = flattenCollectionFiles(tree)

        assertEquals(listOf("folder/photo.jpg", "archive.zip"), files.map { it.path })
        assertTrue(files[1].node is ZipNode)
    }

    @Test
    fun appliesFolderAndFileRenamesUsingOriginalPathKeys() {
        val renames = mapOf("photos" to "Images", "photos/a.jpg" to "alpha.jpg")

        assertEquals("Images/alpha.jpg", applyTreeRenames("photos/a.jpg", renames))
        assertEquals("Images/b.jpg", applyTreeRenames("photos/b.jpg", renames))

        val tree = DirectoryNode(
            "root", "", listOf(
                DirectoryNode("photos", "photos", listOf(FileNode("a", "a.jpg", 1))),
            ),
        ).applyTreeRenames(renames)
        val renamedFolder = tree.entries.single() as DirectoryNode
        assertEquals("Images", renamedFolder.name)
        assertEquals("alpha.jpg", renamedFolder.entries.single().name)
    }

    @Test
    fun sanitizesDownloadPathsAndDetectsCollectionSubfolderNeed() {
        assertEquals("folder/a_b.txt", sanitizeDownloadPath("folder/a:b.txt", false))
        assertEquals("Untitled", sanitizeDownloadPath("\u200B", true))
        assertEquals("HanGeul", sanitizeDownloadPath("한글", true))
        assertEquals("PaIl_ILeum.txt", sanitizeDownloadPath("파일:이름.txt", true))
        assertEquals("cafe.txt", sanitizeDownloadPath("café.txt", true))
        val tree = DirectoryNode("root", "", listOf(FileNode("a", "a.txt", 1), FileNode("b", "b.txt", 1)))
        assertTrue(shouldCreateCollectionSubfolder(tree, "Collection", true))
    }
}

