package com.yijian.translate

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts

/** Prefer the photo grid. Older phones go directly to their gallery's image picker. */
class GalleryImageContract : ActivityResultContract<Unit, Uri?>() {
    override fun createIntent(context: Context, input: Unit): Intent {
        if (ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(context))
            return ActivityResultContracts.PickVisualMedia().createIntent(context, PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        val gallery = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).setType("image/*")
        return if (gallery.resolveActivity(context.packageManager) != null) gallery
            else Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
    }
    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data ?: intent?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri else null
}
