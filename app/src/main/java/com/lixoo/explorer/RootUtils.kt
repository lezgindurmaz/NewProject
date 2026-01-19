package com.lixoo.explorer

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader

object RootUtils {
    fun isRootAvailable(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            os.writeBytes("exit\n")
            os.flush()
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    fun runCommand(command: String): List<String> {
        val output = mutableListOf<String>()
        try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            os.writeBytes("$command\n")
            os.writeBytes("exit\n")
            os.flush()

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.add(line!!)
            }
            process.waitFor()
        } catch (e: Exception) {
            Log.e("RootUtils", "Error running command: $command", e)
        }
        return output
    }
}
