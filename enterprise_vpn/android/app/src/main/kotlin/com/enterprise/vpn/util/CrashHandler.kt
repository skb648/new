package com.enterprise.vpn.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash Handler
 * 
 * Catches uncaught exceptions and logs them for debugging.
 */
object CrashHandler : Thread.UncaughtExceptionHandler {
    
    private const val TAG = "CrashHandler"
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null
    private var context: Context? = null

    fun init(context: Context) {
        this.context = context
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
        Log.i(TAG, "Crash handler initialized")
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        Log.e(TAG, "Uncaught exception in thread ${thread.name}", throwable)
        
        // Log the full stack trace
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        Log.e(TAG, "Stack trace: ${sw.toString()}")
        
        // Save crash log
        saveCrashLog(throwable)
        
        // Call default handler
        defaultHandler?.uncaughtException(thread, throwable)
    }

    private fun saveCrashLog(throwable: Throwable) {
        try {
            context?.let { ctx ->
                val crashFile = File(ctx.filesDir, "vpn_crash.log")
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                crashFile.writeText("$timestamp: ${throwable.javaClass.simpleName}: ${throwable.message}\n${throwable.stackTraceToString()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save crash log", e)
        }
    }
}

/**
 * Extension function to write text to file
 */
private fun File.writeText(text: String) {
    writeText(text)
}

/**
 * Extension to get stack trace as string
 */
private fun Throwable.stackTraceToString(): String {
    val sw = StringWriter()
    val pw = PrintWriter(sw)
    this.printStackTrace(pw)
    return sw.toString()
}
