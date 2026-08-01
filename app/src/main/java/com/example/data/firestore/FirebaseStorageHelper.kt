package com.batchfee.edu.data.firestore

import android.content.Context
import android.net.Uri
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

object FirebaseStorageHelper {

    private const val INSTITUTE_PROFILE_PATH = "institutes/%s/profile_photo.jpg"

    suspend fun uploadInstituteLogo(context: Context, instituteId: String, sourceUri: Uri): String =
        withContext(Dispatchers.IO) {
            val storageRef = FirebaseStorage.getInstance().reference
                .child(INSTITUTE_PROFILE_PATH.format(instituteId))

            context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                storageRef.putStream(inputStream).await()
            } ?: throw IllegalStateException("Unable to read image file for upload.")

            storageRef.downloadUrl.await().toString()
        }

    suspend fun deleteInstituteLogo(instituteId: String) {
        withContext(Dispatchers.IO) {
            try {
                FirebaseStorage.getInstance().reference
                    .child(INSTITUTE_PROFILE_PATH.format(instituteId))
                    .delete()
                    .await()
            } catch (e: Exception) {
                FirebaseCrashlytics.getInstance().recordException(e)
            }
        }
    }
}
