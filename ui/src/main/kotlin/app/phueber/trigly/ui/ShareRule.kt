package app.phueber.trigly.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * The file name a shared or exported rule arrives under.
 *
 * Derived from the rule's name so the person on the other end can tell one from
 * another, and reduced to what every file system and every messaging app will
 * carry unchanged: lower case, ASCII letters and digits, single dashes.
 *
 * Pure and separate from the sharing because it is the part with decisions in
 * it, and because a rule named entirely in a script this strips has to still
 * produce a usable name rather than `trigly-.json`.
 */
fun sharedRuleFileName(ruleName: String): String {
    val slug = ruleName.lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .take(60)
    return "trigly-${slug.ifEmpty { "rule" }}.json"
}

/**
 * Where a shared file is written, and the authority that hands it out.
 *
 * The directory is emptied first, every time. A share writes a copy for one
 * hand-off, and the alternative is a cache that grows by one file per share and
 * keeps rules the person may have since deleted from the app.
 */
private const val SHARED_DIR = "shared"

private fun sharedFile(context: Context, fileName: String): File {
    val dir = File(context.cacheDir, SHARED_DIR)
    dir.deleteRecursively()
    dir.mkdirs()
    return File(dir, fileName)
}

/**
 * A content URI for [json] under [fileName], readable by whoever we hand it to.
 *
 * Separate from [shareRuleIntent] so a test can check the provider is declared
 * correctly. A wrong authority in the manifest throws here, at share time, in
 * front of the person trying to share, and nothing else in the app would ever
 * exercise it.
 */
fun sharedRuleUri(context: Context, fileName: String, json: String): Uri {
    val file = sharedFile(context, fileName)
    file.writeText(json)
    return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
}

/**
 * The chooser to start for sharing one rule.
 *
 * A file rather than plain text in `EXTRA_TEXT`. Text would read fine in a chat
 * and be useless on arrival: importing a rule reads a file through the document
 * picker, so a rule pasted into a message has to be saved as a file by hand
 * before the app on the other end can take it. Sending the file means the round
 * trip works.
 *
 * `EXTRA_TITLE` carries the rule's own name as a hint for receivers that want a
 * subject or a label, an email being the obvious one. It is a hint and not the
 * sheet's own label: checked on API 35, the chooser previews a file share by its
 * file name, which is why [sharedRuleFileName] builds that name out of the
 * rule's name rather than leaving it generic.
 */
fun shareRuleIntent(context: Context, ruleName: String, json: String): Intent {
    val fileName = sharedRuleFileName(ruleName)
    val uri = sharedRuleUri(context, fileName, json)

    val send = Intent(Intent.ACTION_SEND).apply {
        // The rule's own format, not text/plain: an app that can take a JSON
        // file says so, and a chooser full of apps that would mangle it is not
        // a useful chooser.
        type = "application/json"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TITLE, ruleName)
        // The grant the provider's `exported="false"` relies on. Without it the
        // receiving app gets a URI it is not allowed to open.
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    return Intent.createChooser(send, null).apply {
        // The chooser passes the grant on to whatever the person picks. Set on
        // the chooser as well as the inner intent, because the chooser is the
        // intent the system actually starts.
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
