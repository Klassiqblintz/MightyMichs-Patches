package mightymich.morphe.patches.com.whatsapp

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.classDefBy
import app.morphe.patcher.patch.classDefForEach
import app.morphe.patcher.patch.mutableClassDefBy

/**
 * Prevents "Delete for everyone" from wiping local message text.
 *
 * Strategy (3 phases, stops at first hit):
 *   Phase 1: Classic string-based search — finds classes referencing
 *           "delete_for_everyone"/"revoked"/etc, then scans their void
 *           methods for bytecode containing "revoke" or the marker.
 *           This is the FAST path that works on most WhatsApp versions.
 *
 *   Phase 2: Broad-scan fallback — iterates ALL classes in ALL DEX
 *           files (classDefForEach), skips resource/config classes, and
 *           looks for void methods (< 60 instructions) containing
 *           heuristic markers like "delete", "revok", "setDeleted", etc.
 *
 *   Phase 3: Name-pattern fallback — searches ALL classes for void
 *           methods whose names contain setDeleted/markDeleted/wipe/
 *           clearMessage/deleteMessage.
 *
 * WhatsApp 2.26.43.75: Phase 1 fails because the revoke handler class
 * no longer references the classic string markers (or the method has
 * a non-void return type or >120 instructions). Phases 2-3 compensate.
 */
@Suppress("unused")
val keepDeletedMessagesPatch = bytecodePatch(
    name = "Keep deleted messages",
    description = "Show messages that were deleted for everyone. " +
        "May need a fingerprint update after WhatsApp upgrades.",
    default = true,
) {
    compatibleWith(WhatsAppCompatibility.WHATSAPP)

    val highlight by booleanOption(
        key = "highlight",
        default = true,
        title = "Highlight deleted messages",
        description = "Mark deleted messages so they are easier to spot.",
    )

    execute {
        // ================================================================
        // PHASE 1 — Classic string-based search (fast path)
        // ================================================================
        val markers = listOf(
            "Delete for everyone",
            "delete_for_everyone",
            "revoked",
            "REVOKE",
            "message_revoked",
        )
        var hits = 0

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
                    val text = impl.instructions.joinToString("\n") { it.toString() }
                    if (!text.contains("revoke", ignoreCase = true) &&
                        !text.contains(marker)
                    ) {
                        continue
                    }
                    if (impl.instructions.count() > 120) continue
                    try {
                        method.addInstructions(0, "return-void")
                        hits++
                    } catch (_: Exception) {}
                }
            }
            if (hits > 0) break
        }

        // ================================================================
        // PHASE 2 — Broad-scan fallback for all classes in all DEX files
        // ================================================================
        if (hits == 0) {
            val heuristicMarkers = listOf(
                "delete",
                "revok",
                "setDeleted",
                "markAsDeleted",
                "wipeMessage",
                "clearMessage",
            )

            classDefForEach { def ->
                val clazz = try {
                    mutableClassDefBy(def)
                } catch (_: Exception) {
                    return@classDefForEach
                }

                val className = clazz.type
                // Skip obvious non-handler classes
                if (className.contains("R$") ||
                    className.contains("BuildConfig") ||
                    className.contains("Compat")
                ) {
                    return@classDefForEach
                }

                for (method in clazz.methods) {
                    if (method.returnType != "V") continue
                    val impl = method.implementation ?: continue

                    val instrCount = impl.instructions.count()
                    if (instrCount > 60) continue

                    val text = impl.instructions.joinToString("\n") { it.toString() }

                    var matched = false
                    for (hm in heuristicMarkers) {
                        if (text.contains(hm, ignoreCase = true)) {
                            matched = true
                            break
                        }
                    }
                    if (!matched) continue

                    try {
                        method.addInstructions(0, "return-void")
                        hits++
                    } catch (_: Exception) {}
                }
                if (hits > 0) return@classDefForEach
            }
        }

        // ================================================================
        // PHASE 3 — Name-pattern fallback
        // ================================================================
        if (hits == 0) {
            val namePatterns = listOf(
                "setDeleted",
                "setRevoked",
                "markDeleted",
                "wipe",
                "clearMessage",
                "deleteMessage",
            )
            classDefForEach { def ->
                val clazz = try {
                    mutableClassDefBy(def)
                } catch (_: Exception) {
                    return@classDefForEach
                }

                for (method in clazz.methods) {
                    if (method.returnType != "V") continue
                    val impl = method.implementation ?: continue

                    var nameMatch = false
                    for (pattern in namePatterns) {
                        if (method.name.contains(pattern, ignoreCase = true)) {
                            nameMatch = true
                            break
                        }
                    }
                    if (!nameMatch) continue

                    val instrCount = impl.instructions.count()
                    if (instrCount > 60) continue

                    try {
                        method.addInstructions(0, "return-void")
                        hits++
                    } catch (_: Exception) {}
                }
                if (hits > 0) return@classDefForEach
            }
        }

        if (hits == 0) {
            throw PatchException(
                "WhatsApp: no delete-for-everyone / revoke handler matched. " +
                    "Provide APK analysis for this version.",
            )
        }
    }
}

