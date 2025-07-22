package de.uniwuerzburg.omod.io

import de.uniwuerzburg.omod.io.gtfs.clipGTFSFile
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.locationtech.jts.geom.Envelope
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

class GTFSKtTest {
    val bbox_germany_small= Envelope(49.787, 49.79,9.92, 9.93)
    val bbox_korea_small= Envelope(36.3435, 36.345, 127.38,127.40)

    @Test
    fun clipGTFSGermanTest(){
        val input = Paths.get(javaClass.getResource("/clippedGTFSGermanyBig")!!.toURI())
        val expectedFolder = File(javaClass.getResource("/clippedGTFSGermanySmall")!!.toURI())

        val outputBaseDir = File("build/test-output").apply { mkdirs() }
        val actualClippedFolder = File(outputBaseDir, "clippedGTFS")
        clipGTFSTest(input, expectedFolder, outputBaseDir,actualClippedFolder, bbox_germany_small)
    }

    @Test
    fun clipGTFSKoreanTest(){
        val input = Paths.get(javaClass.getResource("/clippedGTFSKoreaBig")!!.toURI())
        val expectedFolder = File(javaClass.getResource("/clippedGTFSKoreaSmall")!!.toURI())
        val outputBaseDir = File("build/test-output").apply { mkdirs() }
        val actualClippedFolder = File(outputBaseDir, "clippedGTFS")
        clipGTFSTest(input, expectedFolder, outputBaseDir,actualClippedFolder, bbox_korea_small)
    }

    fun clipGTFSTest(input: Path, expectedFolder: File, outputBaseDir: File, actualClippedFolder: File, bbBox: Envelope) {
        if (actualClippedFolder.exists()) actualClippedFolder.deleteRecursively()
        val dispatcher = Dispatchers.Default.limitedParallelism(1)
        try {
            clipGTFSFile(
                bbBox,
                input,
                outputBaseDir.toPath(),
                dispatcher
            )

            val files1 = actualClippedFolder.listFiles { _, name -> name.endsWith(".txt") }?.associateBy { it.name }
                ?: error("No .txt files found in clipped output")
            val files2 = expectedFolder.listFiles { _, name -> name.endsWith(".txt") }?.associateBy { it.name }
                ?: error("No .txt files found in expected resource folder")

            assertEquals(files2.keys, files1.keys, "File names differ")

            for (fileName in files1.keys) {
                val actual = files1[fileName]!!.readText().replace("\r\n", "\n")
                val expected = files2[fileName]!!.readText().replace("\r\n", "\n")

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