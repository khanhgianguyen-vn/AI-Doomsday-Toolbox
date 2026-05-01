package com.example.llamadroid.data.db

object ModelBackupPolicy {
    const val LOCAL_IMPORT_REPO_ID = "local-import"
    const val CUSTOM_IMPORT_REPO_PREFIX = "custom-import/"
    const val ONNX_CUSTOM_IMPORT_ASSET_KIND = "custom_import_bundle"

    const val IMPORTED_MODEL_SQL_PREDICATE =
        "((repoId = '$LOCAL_IMPORT_REPO_ID' AND isDownloaded = 0) " +
            "OR repoId LIKE '$CUSTOM_IMPORT_REPO_PREFIX%' " +
            "OR onnxAssetKind = '$ONNX_CUSTOM_IMPORT_ASSET_KIND')"

    fun shouldKeepInPortableBackup(model: ModelEntity): Boolean =
        (model.repoId == LOCAL_IMPORT_REPO_ID && !model.isDownloaded) ||
            model.repoId.startsWith(CUSTOM_IMPORT_REPO_PREFIX) ||
            model.onnxAssetKind == ONNX_CUSTOM_IMPORT_ASSET_KIND
}
