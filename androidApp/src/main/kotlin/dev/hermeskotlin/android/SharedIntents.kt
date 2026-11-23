package dev.hermeskotlin.android

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import dev.hermeskotlin.core.chat.SharedContent

/**
 * What another app shared with Herald through the share sheet, or null when [intent] isn't a share.
 * Files come as `content://` URIs from other apps only: a `file://` path, or a URI into Herald's own
 * storage, could make Herald attach (and send) one of its own private files.
 */
fun sharedContent(context: Context, intent: Intent): SharedContent? {
    val action = intent.action
    if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return null
    val streams = buildList {
        if (action == Intent.ACTION_SEND) {
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(::add)
        } else {
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(::addAll)
        }
        // Some apps only put their files in the clip.
        if (isEmpty()) intent.clipData?.let { clip -> (0 until clip.itemCount).mapNotNullTo(this) { clip.getItemAt(it).uri } }
    }
    return SharedContent(
        text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(),
        subject = intent.getStringExtra(Intent.EXTRA_SUBJECT),
        files = streams.filter { isForeignContent(context, it) }.map(Uri::toString),
    )
}

internal fun isForeignContent(context: Context, uri: Uri): Boolean {
    if (uri.scheme != ContentResolver.SCHEME_CONTENT) return false
    // ContentResolver strips a leading "<userId>@" prefix before routing, while PackageManager.resolveContentProvider
    // does not — strip it first so "0@<own>.attachments" cannot masquerade as an unknown foreign provider.
    val authority = uri.authority?.substringAfterLast('@')?.takeIf { it.isNotBlank() } ?: return false
    if (authority.equals("${context.packageName}.attachments", ignoreCase = true)) return false
    val provider = context.packageManager.resolveContentProvider(authority, 0)
    return provider?.packageName != context.packageName
}
