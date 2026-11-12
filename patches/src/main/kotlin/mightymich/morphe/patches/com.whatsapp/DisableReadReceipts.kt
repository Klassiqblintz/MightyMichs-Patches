package mightymich.morphe.patches.com.whatsapp

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.booleanOption

@Suppress("unused")
val disableReadReceiptsPatch = bytecodePatch(
    name = "Read receipts",
    description = "If enabled, you won't send read receipts (blue ticks).",
    default = true,
) {
    compatibleWith(WhatsAppCompatibility.WHATSAPP)

    val always by booleanOption(
        key = "always",
        default = true,
        title = "Always off",
        description = "Always disable read receipts, ignoring the in-app toggle.",
    )

    execute {
        val markers = listOf("read receipt", "read_receipt", "message_receipt", "receipt_type")
        var hits = 0

        // Phase 1: Classic string-based search
        for (marker in markers) {
            val defs = try {
                classDefByStrings(marker)
            } catch (_: Exception) {
                emptyList()
            }
            for (def in defs) {
                val clazz = try {
                    mutableClassDefBy(def)
                } catch (_: Exception) {
                    continue
                }
                for (method in clazz.methods) {
                    if (method.returnType != "V") continue
                    val impl = method.implementation ?: continue
                    if (impl.instructions.count() > 100) continue
                    val text = impl.instructions.joinToString("\n") { it.toString() }
                    if (!text.contains("receipt", ignoreCase = true)) continue
                    try {
                        if (always != false) {
                            method.addInstructions(0, "return-void")
                        } else {
                            method.addInstructions(
                                0,
                                """
                                    const-string v0, "${ModPrefs.PREFS_NAME}"
                                    const/4 v1, 0x0
                                    invoke-virtual {p0, v0, v1}, Landroid/content/Context;->getSharedPreferences(Ljava/lang/String;I)Landroid/content/SharedPreferences;
                                    move-result-object v0
                                    const-string v1, "${ModPrefs.DISABLE_READ_RECEIPTS}"
                                    const/4 v2, 0x0
                                    invoke-interface {v0, v1, v2}, Landroid/content/SharedPreferences;->getBoolean(Ljava/lang/String;Z)Z
                                    move-result v0
                                    if-eqz v0, :mm_send
                                    return-void
                                    :mm_send
                                """,
                            )
                        }
                        hits++
                    } catch (_: Exception) {
                    }
                }
            }
            if (hits > 0) break
        }

        // Phase 2: Heuristic bytecode scan — search ALL classes for methods
        // that contain "receipt" in their bytecode and return void
        if (hits == 0) {
            try {
                classDefForEach@ { classDef ->
                    val type = classDef.type
                    // Only patch WhatsApp's own classes
                    if (type.contains("com.whatsapp") || type.contains("whatsapp")) {
                        val mutableClass = mutableClassDefBy(classDef)
                        for (method in mutableClass.methods) {
                            if (method.returnType != "V") continue
                            val impl = method.implementation ?: continue
                            if (impl.instructions.count() > 100) continue
                            val text = impl.instructions.joinToString("\n") { it.toString() }
                            if (!text.contains("receipt", ignoreCase = true)) continue
                            if (always != false) {
                                method.addInstructions(0, "return-void")
                            } else {
                                method.addInstructions(
                                    0,
                                    """
                                        const-string v0, "${ModPrefs.PREFS_NAME}"
                                        const/4 v1, 0x0
                                        invoke-virtual {p0, v0, v1}, Landroid/content/Context;->getSharedPreferences(Ljava/lang/String;I)Landroid/content/SharedPreferences;
                                        move-result-object v0
                                        const-string v1, "${ModPrefs.DISABLE_READ_RECEIPTS}"
                                        const/4 v2, 0x0
                                        invoke-interface {v0, v1, v2}, Landroid/content/SharedPreferences;->getBoolean(Ljava/lang/String;Z)Z
                                        move-result v0
                                        if-eqz v0, :mm_send
                                        return-void
                                        :mm_send
                                    """,
                                )
                            }
                            hits++
                            throw classDefForEach@ Exception("found")
                        }
                    }
                }
            } catch (e: Exception) {
                // Found it in phase 2, exit early
            }
        }

        // Phase 3: Method name pattern scan — find methods with "Receipt"/"read" in name
        if (hits == 0) {
            try {
                classDefForEach2@ { classDef ->
                    val mutableClass = mutableClassDefBy(classDef)
                    for (method in mutableClass.methods) {
                        val name = method.name.lowercase()
                        if (name.contains("receipt") || name.contains("readreceipt") || name.contains("rdreceipt")) {
                            if (method.returnType != "V") continue
                            val impl = method.implementation ?: continue
                            if (impl.instructions.count() > 100) continue
                            if (always != false) {
                                method.addInstructions(0, "return-void")
                            } else {
                                method.addInstructions(
                                    0,
                                    """
                                        const-string v0, "${ModPrefs.PREFS_NAME}"
                                        const/4 v1, 0x0
                                        invoke-virtual {p0, v0, v1}, Landroid/content/Context;->getSharedPreferences(Ljava/lang/String;I)Landroid/content/SharedPreferences;
                                        move-result-object v0
                                        const-string v1, "${ModPrefs.DISABLE_READ_RECEIPTS}"
                                        const/4 v2, 0x0
                                        invoke-interface {v0, v1, v2}, Landroid/content/SharedPreferences;->getBoolean(Ljava/lang/String;Z)Z
                                        move-result v0
                                        if-eqz v0, :mm_send
                                        return-void
                                        :mm_send
                                    """,
                                )
                            }
                            hits++
                            throw classDefForEach2@ Exception("found")
                        }
                    }
                }
            } catch (e: Exception) {
                // Found it in phase 3, exit early
            }
        }

        if (hits == 0) {
            throw PatchException(
                "WhatsApp: no read-receipt send sites matched. Re-derive for this APK version.",
            )
        }
    }
}
