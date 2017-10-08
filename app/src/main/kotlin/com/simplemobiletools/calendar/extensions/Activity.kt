package com.simplemobiletools.calendar.extensions

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.support.v4.content.ContextCompat
import com.simplemobiletools.calendar.R
import com.simplemobiletools.calendar.activities.SimpleActivity
import com.simplemobiletools.commons.extensions.toast
import java.io.File

fun Activity.hasGetAccountsPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.GET_ACCOUNTS) == PackageManager.PERMISSION_GRANTED

fun SimpleActivity.shareEvents(ids: List<Int>) {
    val file = getTempFile()
    if (file == null) {
        toast(R.string.unknown_error_occurred)
        return
    }
}

fun Activity.getTempFile(): File? {
    val folder = File(cacheDir, "events")
    if (!folder.exists()) {
        if (!folder.mkdir()) {
            toast(R.string.unknown_error_occurred)
            return null
        }
    }

    return File(folder, "events.ics")
}
