package com.tiktok.regionpatcher.core

import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.DexFile
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction3rc
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.rewriter.DexRewriter
import com.android.tools.smali.dexlib2.rewriter.Rewriter
import com.android.tools.smali.dexlib2.rewriter.RewriterModule
import com.android.tools.smali.dexlib2.rewriter.Rewriters
import java.io.File

private const val TELEPHONY = "Landroid/telephony/TelephonyManager;"

/**
 * Rewrites DEX bytecode so TelephonyManager region lookups return fixed strings.
 */
object DexTelephonyPatcher {

    fun patchDexFile(input: File, output: File, region: RegionConfig, report: PatchReport) {
        val dexFile = DexFileFactory.loadDexFile(input, Opcodes.getDefault())
        val counter = PatchCounter()
        val rewriter = DexRewriter(TelephonyRewriterModule(region, counter))
        val patched: DexFile = rewriter.dexFileRewriter.rewrite(dexFile)
        DexFileFactory.writeDexFile(output.absolutePath, patched)
        report.patchedSites += counter.patches
        if (counter.patches > 0) {
            report.details.add("${input.name}: ${counter.patches} patch(es)")
        }
    }

    fun patchDexBytes(bytes: ByteArray, region: RegionConfig, report: PatchReport): ByteArray {
        val tmpIn = File.createTempFile("dex_in_", ".dex")
        val tmpOut = File.createTempFile("dex_out_", ".dex")
        try {
            tmpIn.writeBytes(bytes)
            patchDexFile(tmpIn, tmpOut, region, report)
            return tmpOut.readBytes()
        } finally {
            tmpIn.delete()
            tmpOut.delete()
        }
    }

    private class PatchCounter(var patches: Int = 0)

    private class TelephonyRewriterModule(
        private val region: RegionConfig,
        private val counter: PatchCounter,
    ) : RewriterModule() {

        override fun getMethodRewriter(rewriters: Rewriters): Rewriter<Method> = Rewriter { method ->
            val impl = method.implementation ?: return@Rewriter method
            val instructions = impl.instructions.toList()
            if (!referencesTelephony(instructions)) {
                return@Rewriter method
            }
            val patched = patchInstructions(instructions) ?: return@Rewriter method
            ImmutableMethod(
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
        }

        private fun referencesTelephony(instructions: List<Instruction>): Boolean {
            return instructions.any { getTelephonyMethod(it) != null }
        }

        private fun patchInstructions(instructions: List<Instruction>): List<Instruction>? {
            val out = ArrayList<Instruction>(instructions.size)
            var changed = false
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
                            counter.patches++
                            changed = true
                            i = nextIdx + 1
                            continue
                        }
                    }
                }
                out.add(instructions[i])
                i++
            }
            return if (changed) out else null
        }

        private fun nextMeaningfulIndex(instructions: List<Instruction>, start: Int): Int? {
            for (idx in start until instructions.size) {
                val line = instructions[idx]
                // Skip only debug pseudo-instructions if present (rare in dex).
                if (line.opcode == Opcode.NOP) continue
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
    }
}
