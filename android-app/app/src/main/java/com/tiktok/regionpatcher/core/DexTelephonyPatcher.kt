package com.tiktok.regionpatcher.core

import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.DexFile
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.MethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction3rc
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import java.io.File
import java.io.RandomAccessFile

private const val TELEPHONY = "Landroid/telephony/TelephonyManager;"
private val TELEPHONY_MARKER = TELEPHONY.toByteArray(Charsets.UTF_8)

/**
 * Patches only methods that call TelephonyManager region getters.
 *
 * Unlike a full [com.android.tools.smali.dexlib2.rewriter.DexRewriter] pass, this
 * touches only classes/methods that actually reference TelephonyManager. Dex
 * files without that type are copied verbatim (fast path for TikTok's many
 * split dex files).
 */
object DexTelephonyPatcher {

    fun patchDexFile(
        input: File,
        output: File,
        region: RegionConfig,
        report: PatchReport,
        onProgress: ((String) -> Unit)? = null,
    ) {
        val label = input.name
        if (!fileContainsMarker(input)) {
            onProgress?.invoke("$label: пропуск (нет TelephonyManager)")
            input.copyTo(output, overwrite = true)
            return
        }

        onProgress?.invoke("$label: загрузка…")
        val dexFile = DexFileFactory.loadDexFile(input, Opcodes.getDefault())

        var patches = 0
        var classesChanged = 0
        val totalClasses = dexFile.classes.count()
        var scanned = 0

        val outClasses = ArrayList<ClassDef>(totalClasses.coerceAtLeast(16))
        for (classDef in dexFile.classes) {
            scanned++
            if (scanned % 2000 == 0) {
                onProgress?.invoke("$label: классы $scanned / $totalClasses…")
            }
            val (patchedClass, classPatches) = patchClassDef(classDef, region)
            patches += classPatches
            if (classPatches > 0) classesChanged++
            outClasses.add(patchedClass)
        }

        report.patchedSites += patches
        if (patches > 0) {
            report.details.add("$label: $patches patch(es) in $classesChanged class(es)")
            onProgress?.invoke("$label: запись ($patches патчей)…")
            DexFileFactory.writeDexFile(
                output.absolutePath,
                object : DexFile {
                    override fun getOpcodes() = Opcodes.getDefault()
                    override fun getClasses(): Set<ClassDef> = LinkedHashSet(outClasses)
                },
            )
        } else {
            onProgress?.invoke("$label: вызовов не найдено, копирую как есть")
            input.copyTo(output, overwrite = true)
        }
    }

    fun patchDexBytes(
        bytes: ByteArray,
        region: RegionConfig,
        report: PatchReport,
        dexName: String,
        onProgress: ((String) -> Unit)? = null,
    ): ByteArray {
        if (!bytesContainMarker(bytes)) {
            onProgress?.invoke("$dexName: пропуск (нет TelephonyManager)")
            return bytes
        }
        val tmpIn = File.createTempFile("dex_in_", ".dex")
        val tmpOut = File.createTempFile("dex_out_", ".dex")
        try {
            tmpIn.writeBytes(bytes)
            patchDexFile(tmpIn, tmpOut, region, report, onProgress)
            return tmpOut.readBytes()
        } finally {
            tmpIn.delete()
            tmpOut.delete()
        }
    }

    private fun patchClassDef(classDef: ClassDef, region: RegionConfig): Pair<ClassDef, Int> {
        var patches = 0
        var directChanged = false
        val newDirect = ArrayList<Method>()
        for (method in classDef.directMethods) {
            val (patched, count) = patchMethod(method, region)
            patches += count
            if (patched !== method) directChanged = true
            newDirect.add(patched)
        }

        var virtualChanged = false
        val newVirtual = ArrayList<Method>()
        for (method in classDef.virtualMethods) {
            val (patched, count) = patchMethod(method, region)
            patches += count
            if (patched !== method) virtualChanged = true
            newVirtual.add(patched)
        }

        if (!directChanged && !virtualChanged) {
            return classDef to patches
        }

        return ImmutableClassDef(
            classDef.type,
            classDef.accessFlags,
            classDef.superclass,
            classDef.interfaces,
            classDef.sourceFile,
            classDef.annotations,
            classDef.staticFields,
            classDef.instanceFields,
            newDirect,
            newVirtual,
        ) to patches
    }

    private fun patchMethod(method: Method, region: RegionConfig): Pair<Method, Int> {
        val impl = method.implementation ?: return method to 0
        if (!implementationMayReferenceTelephony(impl)) {
            return method to 0
        }
        val instructions = impl.instructions.toList()
        val (patched, count) = patchInstructions(instructions, region) ?: return method to 0
        val newMethod = ImmutableMethod(
            method.definingClass,
            method.name,
            method.parameters,
            method.returnType,
            method.accessFlags,
            method.annotations,
            method.hiddenApiRestrictions,
            ImmutableMethodImplementation(
                impl.registerCount,
                patched,
                impl.tryBlocks,
                impl.debugItems,
            ),
        )
        return newMethod to count
    }

    private fun implementationMayReferenceTelephony(impl: MethodImplementation): Boolean {
        for (inst in impl.instructions) {
            if (getTelephonyMethod(inst) != null) return true
        }
        return false
    }

    private fun patchInstructions(
        instructions: List<Instruction>,
        region: RegionConfig,
    ): Pair<List<Instruction>, Int>? {
        val out = ArrayList<Instruction>(instructions.size)
        var patches = 0
        var i = 0
        while (i < instructions.size) {
            val methodName = getTelephonyMethod(instructions[i])
            if (methodName != null && methodName in RegionConfig.PATCHABLE_METHODS) {
                val nextIdx = nextMeaningfulIndex(instructions, i + 1)
                if (nextIdx != null) {
                    val next = instructions[nextIdx]
                    if (next.opcode == Opcode.MOVE_RESULT_OBJECT && next is OneRegisterInstruction) {
                        val reg = next.registerA
                        val value = region.valueForMethod(methodName)
                        out.add(
                            ImmutableInstruction21c(
                                Opcode.CONST_STRING,
                                reg,
                                ImmutableStringReference(value),
                            ),
                        )
                        patches++
                        i = nextIdx + 1
                        continue
                    }
                }
            }
            out.add(instructions[i])
            i++
        }
        return if (patches > 0) out to patches else null
    }

    private fun nextMeaningfulIndex(instructions: List<Instruction>, start: Int): Int? {
        for (idx in start until instructions.size) {
            if (instructions[idx].opcode == Opcode.NOP) continue
            return idx
        }
        return null
    }

    private fun getTelephonyMethod(inst: Instruction): String? {
        if (inst.opcode != Opcode.INVOKE_VIRTUAL && inst.opcode != Opcode.INVOKE_INTERFACE) {
            return null
        }
        val ref = when (inst) {
            is Instruction35c -> inst.reference
            is Instruction3rc -> inst.reference
            else -> return null
        }
        if (ref !is MethodReference) return null
        if (ref.definingClass != TELEPHONY) return null
        if (ref.returnType != "Ljava/lang/String;") return null
        if (ref.parameterTypes.isNotEmpty()) return null
        return ref.name
    }

    private fun fileContainsMarker(file: File): Boolean {
        RandomAccessFile(file, "r").use { raf ->
            val buffer = ByteArray(64 * 1024)
            var leftover = ByteArray(0)
            while (true) {
                val read = raf.read(buffer)
                if (read <= 0) break
                val chunk = if (leftover.isEmpty()) {
                    buffer.copyOf(read)
                } else {
                    leftover + buffer.copyOf(read)
                }
                if (bytesContainMarker(chunk)) return true
                leftover = if (chunk.size > TELEPHONY_MARKER.size) {
                    chunk.copyOfRange(chunk.size - TELEPHONY_MARKER.size, chunk.size)
                } else {
                    chunk
                }
            }
            return bytesContainMarker(leftover)
        }
    }

    private fun bytesContainMarker(bytes: ByteArray): Boolean {
        if (bytes.size < TELEPHONY_MARKER.size) return false
        outer@ for (i in 0..bytes.size - TELEPHONY_MARKER.size) {
            for (j in TELEPHONY_MARKER.indices) {
                if (bytes[i + j] != TELEPHONY_MARKER[j]) continue@outer
            }
            return true
        }
        return false
    }
}
