package com.njlabs.showjava.workers.decompiler

import android.content.Context
import androidx.work.WorkerParameters
import com.googlecode.dex2jar.Method
import com.googlecode.dex2jar.ir.IrMethod
import com.googlecode.dex2jar.reader.DexFileReader
import com.googlecode.dex2jar.v3.Dex2jar
import com.googlecode.dex2jar.v3.DexExceptionHandler
import com.njlabs.showjava.R
import com.njlabs.showjava.utils.StringTools
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.ClassDef
import org.jf.dexlib2.iface.DexFile
import org.jf.dexlib2.immutable.ImmutableDexFile
import org.objectweb.asm.tree.MethodNode
import timber.log.Timber
import java.lang.Exception

class JarExtractionWorker(context: Context, params: WorkerParameters) : BaseWorker(context, params) {

    private var ignoredLibs: ArrayList<String>? = ArrayList()

    private fun loadIgnoredLibs() {
        val ignoredList =
            if (data.getBoolean("shouldIgnoreLibs", false)) "ignored.list" else "ignored_basic.list"
        context.assets.open(ignoredList).bufferedReader().useLines {
            it.map { line -> ignoredLibs?.add(StringTools.toClassName(line)) }
        }
    }

    @Throws(Exception::class)
    private fun convertApkToDex() {

        Timber.i("Starting APK to DEX Conversion")

        var dexFile: DexFile = DexFileFactory.loadDexFile("", Opcodes.getDefault())

        val classes = ArrayList<ClassDef>()
        sendStatus(context.getString(R.string.optimizing))

        for (classDef in dexFile.classes) {
            if (!isIgnored(classDef.type)) {
                val currentClass = classDef.type
                sendStatus(
                    context.getString(R.string.optimizingClasses),
                    currentClass.replace("Processing ".toRegex(), "")
                )
                classes.add(classDef)
            }
        }

        Timber.i("Output directory: $workingDirectory")
        sendStatus(context.getString(R.string.mergingClasses))
        dexFile = ImmutableDexFile(Opcodes.getDefault(), classes)

        DexFileFactory.writeDexFile(
            this.outputDexFile.canonicalPath,
            dexFile
        )

        Timber.i("DEX file location: ${this.outputDexFile}")
    }

    @Throws(Exception::class)
    private fun convertDexToJar() {
        Timber.i("Starting DEX to JAR Conversion")
        sendStatus("dex2jar")

        val reuseReg = false // reuse register while generate java .class file
        val topologicalSort1 = false // same with --topological-sort/-ts
        val topologicalSort = false // sort block by topological, results in more readable code
        val verbose = true // show progress
        val debugInfo = false // translate debug info
        val printIR = false // print ir to System.out
        val optimizeSynchronized = true // Optimise-synchronised

        if (outputDexFile.exists() && outputDexFile.isFile) {
            val dexExceptionHandlerMod = DexExceptionHandlerMod()
            val reader = DexFileReader(outputDexFile)
            val dex2jar = Dex2jar.from(reader)
                .reUseReg(reuseReg)
                .topoLogicalSort(topologicalSort || topologicalSort1)
                .skipDebug(!debugInfo)
                .optimizeSynchronized(optimizeSynchronized)
                .printIR(printIR)
                .verbose(verbose)
            dex2jar.exceptionHandler = dexExceptionHandlerMod
            dex2jar.to(outputJarFile)
            Timber.i("Clearing cache")
            outputDexFile.delete()
        }
    }


    private fun isIgnored(className: String): Boolean {
        return ignoredLibs!!.any { className.startsWith(it) }
    }

    private inner class DexExceptionHandlerMod : DexExceptionHandler {
        override fun handleFileException(e: Exception) {
            Timber.d("Dex2Jar Exception $e")
        }
        override fun handleMethodTranslateException(
            method: Method,
            irMethod: IrMethod,
            methodNode: MethodNode,
            e: Exception
        ) {
            Timber.d("Dex2Jar Exception $e")
        }
    }

    override fun doWork(): Result {
        Timber.tag("JarExtraction")
        buildNotification(context.getString(R.string.extractingJar))

        super.doWork()

        try {
            loadIgnoredLibs()
            convertApkToDex()
        } catch (e: Exception) {
            return exit(e)
        }

        if (decompiler != "jadx") {
            convertDexToJar()
        }
        return Result.SUCCESS
    }
}
