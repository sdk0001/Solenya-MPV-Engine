package `is`.xyz.mpv

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

object Utils {
    private const val TAG = "mpv"

    private fun copyAssetFile(assetManager: AssetManager, filename: String, outFile: File): Boolean {
        var input: InputStream? = null
        var output: OutputStream? = null
        return try {
            input = assetManager.open(filename, AssetManager.ACCESS_STREAMING)
            val assetSize = input.available().toLong()
            if (outFile.length() == assetSize) {
                Log.v(TAG, "Skipping asset copy, file is current: $filename")
                true
            } else {
                output = FileOutputStream(outFile)
                input.copyTo(output)
                Log.i(TAG, "Copied asset: $filename")
                true
            }
        } catch (error: IOException) {
            Log.e(TAG, "Failed to copy asset: $filename", error)
            false
        } finally {
            output?.close()
            input?.close()
        }
    }

    private fun writeFontsConf(context: Context, configFile: File) {
        val fontsConfig = """
            <fontconfig>
            <dir>/system/fonts/</dir>
            <dir>/product/fonts/</dir>
            <cachedir>${context.cacheDir.path}</cachedir>
            <alias><family>serif</family><prefer><family>Noto Serif</family></prefer></alias>
            <alias><family>sans-serif</family><prefer><family>Roboto</family><family>Noto Sans</family></prefer></alias>
            <alias><family>monospace</family><prefer><family>Droid Sans Mono</family></prefer></alias>
            </fontconfig>
        """.trimIndent()

        try {
            configFile.writeText(fontsConfig)
        } catch (error: IOException) {
            Log.w(TAG, "Failed to write fonts.conf", error)
        }
    }

    fun copyAssets(context: Context) {
        val configDir = context.filesDir
        copyAssetFile(context.assets, "cacert.pem", File(configDir, "cacert.pem"))
        File(configDir, "subfont.ttf").delete()
        writeFontsConf(context, File(configDir, "fonts.conf"))
    }
}
