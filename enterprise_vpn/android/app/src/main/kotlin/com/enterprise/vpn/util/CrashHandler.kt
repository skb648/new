package com.enterprise.vpn.util

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash Handler
 * 
 * Global crash handler that captures uncaught exceptions,
 * logs them to file, and provides graceful error reporting.
 */
object CrashHandler : Thread.UncaughtExceptionHandler {

    private const val TAG = "CrashHandler"
    private const val CRASH_DIR = "crashes"
    private const val MAX_CRASH_FILES = 5

    private lateinit var context: Context
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null
    private var crashDirectory: File? = null

    /**
     * Initialize crash handler
     */
    fun initialize(context: Context) {
        this.context = context.applicationContext
        
        // Create crash directory
        crashDirectory = File(context.filesDir, CRASH_DIR).apply {
            if (!exists()) {
                mkdirs()
            }
        }
        
        // Save default handler
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        
        // Set ourselves as the default handler
        Thread.setDefaultUncaughtExceptionHandler(this)
        
        Log.d(TAG, "Crash handler initialized")
        
        // Clean up old crash files
        cleanupOldCrashes()
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        Log.e(TAG, "Uncaught exception in thread: ${thread.name}", throwable)
        
        try {
            // Write crash report
            writeCrashReport(thread, throwable)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write crash report", e)
        }
        
        // Let the default handler handle it (or kill the process)
        defaultHandler?.uncaughtException(thread, throwable)
            ?: run {
                Process.killProcess(Process.myPid())
                System.exit(1)
            }
    }

    /**
     * Write crash report to file
     */
    private fun writeCrashReport(thread: Thread, throwable: Throwable) {
        val crashDir = crashDirectory ?: return
        
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val crashFile = File(crashDir, "crash_$timestamp.txt")
        
        Log.d(TAG, "Writing crash report to: ${crashFile.absolutePath}")
        
        FileWriter(crashFile, false).use { writer ->
            PrintWriter(writer).use { printWriter ->
                // Header
                printWriter.println("=".repeat(60))
                printWriter.println("ENTERPRISE VPN CRASH REPORT")
                printWriter.println("=".repeat(60))
                printWriter.println()
                
                // Timestamp
                printWriter.println("Timestamp: ${Date()}")
                printWriter.println()
                
                // Device info
                printWriter.println("-".repeat(40))
                printWriter.println("DEVICE INFO")
                printWriter.println("-".repeat(40))
                printWriter.println("Manufacturer: ${Build.MANUFACTURER}")
                printWriter.println("Model: ${Build.MODEL}")
                printWriter.println("Device: ${Build.DEVICE}")
                printWriter.println("Product: ${Build.PRODUCT}")
                printWriter.println("Android Version: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                printWriter.println("Build: ${Build.DISPLAY}")
                printWriter.println()
                
                // App info
                printWriter.println("-".repeat(40))
                printWriter.println("APP INFO")
                printWriter.println("-".repeat(40))
                try {
                    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                    printWriter.println("Version: ${packageInfo.versionName}")
                    printWriter.println("Version Code: ${packageInfo.versionCode}")
                } catch (e: Exception) {
                    printWriter.println("Version: Unknown")
                }
                printWriter.println("Package: ${context.packageName}")
                printWriter.println()
                
                // Thread info
                printWriter.println("-".repeat(40))
                printWriter.println("THREAD INFO")
                printWriter.println("-".repeat(40))
                printWriter.println("Thread: ${thread.name} (id: ${thread.id})")
                printWriter.println("Thread State: ${thread.state}")
                printWriter.println("Thread Group: ${thread.threadGroup?.name}")
                printWriter.println()
                
                // Stack trace
                printWriter.println("-".repeat(40))
                printWriter.println("STACK TRACE")
                printWriter.println("-".repeat(40))
                throwable.printStackTrace(printWriter)
                printWriter.println()
                
                // Cause chain
                var cause = throwable.cause
                while (cause != null) {
                    printWriter.println("-".repeat(40))
                    printWriter.println("CAUSED BY: ${cause.javaClass.name}")
                    printWriter.println("-".repeat(40))
                    cause.printStackTrace(printWriter)
                    printWriter.println()
                    cause = cause.cause
                }
                
                // Memory info
                printWriter.println("-".repeat(40))
                printWriter.println("MEMORY INFO")
                printWriter.println("-".repeat(40))
                val runtime = Runtime.getRuntime()
                printWriter.println("Max Memory: ${runtime.maxMemory() / 1024 / 1024}MB")
                printWriter.println("Total Memory: ${runtime.totalMemory() / 1024 / 1024}MB")
                printWriter.println("Free Memory: ${runtime.freeMemory() / 1024 / 1024}MB")
                printWriter.println()
                
                // Footer
                printWriter.println("=".repeat(60))
                printWriter.println("END OF CRASH REPORT")
                printWriter.println("=".repeat(60))
            }
        }
    }

    /**
     * Clean up old crash files
     */
    private fun cleanupOldCrashes() {
        val crashDir = crashDirectory ?: return
        
        val crashFiles = crashDir.listFiles()?.sortedByDescending { it.lastModified() }
        
        crashFiles?.let { files ->
            if (files.size > MAX_CRASH_FILES) {
                files.drop(MAX_CRASH_FILES).forEach { file ->
                    Log.d(TAG, "Deleting old crash file: ${file.name}")
                    file.delete()
                }
            }
        }
    }

    /**
     * Check if crashes exist
     */
    fun hasCrashReports(): Boolean {
        val crashDir = crashDirectory ?: return false
        return crashDir.listFiles()?.isNotEmpty() == true
    }

    /**
     * Get crash reports
     */
    fun getCrashReports(): List<File> {
        val crashDir = crashDirectory ?: return emptyList()
        return crashDir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    /**
     * Delete all crash reports
     */
    fun clearCrashReports() {
        val crashDir = crashDirectory ?: return
        crashDir.listFiles()?.forEach { it.delete() }
        Log.d(TAG, "All crash reports cleared")
    }

    /**
     * Get last crash report content
     */
    fun getLastCrashReport(): String? {
        val crashDir = crashDirectory ?: return null
        val lastCrash = crashDir.listFiles()?.maxByOrNull { it.lastModified() }
        return lastCrash?.readText()
    }

    /**
     * Log non-fatal exception
     */
    fun logException(throwable: Throwable, message: String? = null) {
        Log.e(TAG, "Non-fatal exception: $message", throwable)
        
        try {
            val crashDir = crashDirectory ?: return
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val logFile = File(crashDir, "exception_$timestamp.txt")
            
            FileWriter(logFile, false).use { writer ->
                PrintWriter(writer).use { printWriter ->
                    printWriter.println("NON-FATAL EXCEPTION")
                    if (message != null) {
                        printWriter.println("Message: $message")
                    }
                    printWriter.println("Timestamp: ${Date()}")
                    printWriter.println()
                    throwable.printStackTrace(printWriter)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log non-fatal exception", e)
        }
    }
}
