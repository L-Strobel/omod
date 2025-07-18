package de.uniwuerzburg.omod.io

import com.github.ajalt.clikt.completion.CompletionCandidates
import de.uniwuerzburg.omod.core.Omod
import de.uniwuerzburg.omod.io.geojson.readGeoJsonGeom
import de.uniwuerzburg.omod.io.gtfs.clipGTFSFile
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Envelope
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.Path

class GTFSKtTest {
    val bbox_germany_small= Envelope(49.787, 49.79,9.92, 9.93)
    @Test
    fun clipGTFSGermanTest(){
        val input = Paths.get(javaClass.getResource("/clippedGTFSGermanyBig")!!.toURI())
        val expectedFolder = File(javaClass.getResource("/clippedGTFSGermanySmall")!!.toURI())

        val outputBaseDir = File("build/test-output").apply { mkdirs() }
        val actualClippedFolder = File(outputBaseDir, "clippedGTFS")
        clipGTFSTest(input, actualClippedFolder, outputBaseDir,actualClippedFolder)
    }

    @Test
    fun clipGTFSKoreanTest(){
        val input = Paths.get(javaClass.getResource("/clippedGTFSKoreaBig")!!.toURI())
        val expectedFolder = File(javaClass.getResource("/clippedGTFSKoreaSmall")!!.toURI())
        val outputBaseDir = File("build/test-output").apply { mkdirs() }
        val actualClippedFolder = File(outputBaseDir, "clippedGTFS")
        clipGTFSTest(input, actualClippedFolder, outputBaseDir,actualClippedFolder)
    }


    fun clipGTFSTest(input: Path, expectedFolder: File, outputBaseDir: File, actualClippedFolder: File) {
        if (actualClippedFolder.exists()) actualClippedFolder.deleteRecursively()

        try {
            clipGTFSFile(
                bbox_germany_small,
                input,
                outputBaseDir.toPath(),
                Dispatchers.Default
            )

            val files1 = actualClippedFolder.listFiles { _, name -> name.endsWith(".txt") }?.associateBy { it.name }
                ?: error("No .txt files found in clipped output")
            val files2 = expectedFolder.listFiles { _, name -> name.endsWith(".txt") }?.associateBy { it.name }
                ?: error("No .txt files found in expected resource folder")

            assertEquals(files2.keys, files1.keys, "File names differ")

            for (fileName in files1.keys) {
                val actual = files1[fileName]!!.readText()
                val expected = files2[fileName]!!.readText()
                assertEquals(expected, actual, "Mismatch in file: $fileName")
            }
        } finally {
            // Clean up after test
            if (outputBaseDir.exists()) outputBaseDir.deleteRecursively()
        }
    }

    @Test
    fun clipKoreanGTFS(){

    }
}