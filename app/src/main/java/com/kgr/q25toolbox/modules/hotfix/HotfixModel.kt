package com.kgr.q25toolbox.modules.hotfix

import androidx.annotation.StringRes

enum class HotfixId {
    BT_AUDIO,
    CAMERA_50MP,
    KEYMINT_TEE,
    VECTOR_DEX2OAT
}

enum class HotfixStatus {
    ACTIVE,
    AVAILABLE,
    NOT_APPLICABLE,
    PREREQUISITE_MISSING,
    INCOMPATIBLE
}

data class HotfixItem(
    val id: HotfixId,
    @StringRes val titleRes: Int,
    @StringRes val descRes: Int,
    val requiresReboot: Boolean,
    val status: HotfixStatus,
    val statusDetail: String? = null,
    val isEnabled: Boolean = false,
    val isLiveApplied: Boolean = false
)
