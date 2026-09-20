package app.phueber.trigly.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * What a name is reduced to before it can be part of a file name.
 *
 * Kept to what every file system and every messaging app will carry unchanged:
 * lower case, ASCII letters and digits, single dashes. One copy of this, used
 * by every kind of hand-off, because a second copy of the same regex is a
 * second place for the rules to drift apart.
 *
 * It can return an empty string. Each caller decides what to put in its place,
 * since the word that reads best there says what kind of thing arrived.
 */
private fun fileNameSlug(name: String): String = name.lowercase()
    .replace(Regex("[^a-z0-9]+"), "-")
    .trim('-')
    .take(60)

/**
 * The file name a shared or exported rule arrives under.
 *
 * Derived from the rule's name so the person on the other end can tell one from
 * another.
 *
 * Pure and separate from the sharing because it is the part with decisions in
 * it, and because a rule named entirely in a script [fileNameSlug] strips has
 * to still produce a usable name rather than `trigly-.json`.
 */
fun sharedRuleFileName(ruleName: String): String =
    "trigly-${fileNameSlug(ruleName).ifEmpty { "rule" }}.json"

/**
 * The file name a shared folder arrives under.
 *
 * The `folder` in the middle is not decoration. A folder called "Car" and a
 * rule called "Car" would otherwise arrive as the same file name, and the two
 * files are not the same kind of thing: one is a rule, the other is every rule
 * that was under a heading. The receiving end has nothing else to tell them
 * apart by, and on most receivers the second one to arrive silently replaces
 * the first.
 *
 * The fallback is the folder's own word for the same reason: a folder named
 * only in a script that strips to nothing still says what the file holds.
 */
fun sharedFolderFileName(folderName: String): String {
    val slug = fileNameSlug(folderName)
    return if (slug.isEmpty()) "trigly-folder.json" else "trigly-folder-$slug.json"
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
 */
fun shareRuleIntent(context: Context, ruleName: String, json: String): Intent =
    shareJsonIntent(context, label = ruleName, fileName = sharedRuleFileName(ruleName), json = json)

/**
 * The chooser to start for sharing every rule under one folder heading.
 *
 * The same hand-off as [shareRuleIntent], and deliberately the same *file*: a
 * folder is encoded by the list form of `RuleJson.encode`, which is what
 * "Export all" already writes, so the other end imports a folder through the
 * one import path that exists rather than through a second format that would
 * have to be kept working.
 *
 * Only the folder's display name is carried, not the fact that it was a folder.
 * A folder is a name typed on each rule rather than a thing of its own, so
 * there is nothing else to send. Whether the rules land back under that heading
 * on the other device is decided by the `folder` each rule carries in the file.
 */
fun shareFolderIntent(context: Context, folderName: String, json: String): Intent =
    shareJsonIntent(context, label = folderName, fileName = sharedFolderFileName(folderName), json = json)

/**
 * The one hand-off, under whatever name the caller is sending it as.
 *
 * [label] and [fileName] are separate parameters because they are answers to
 * different questions. [label] is what to call this hand-off to a receiver that
 * wants a subject or a heading, an email being the obvious one. [fileName] is
 * what the file is called, and the chooser previews a file share by that name
 * rather than by `EXTRA_TITLE` (checked on API 35), which is why both are built
 * out of the real name of the thing being sent.
 *
 * Private, with a named wrapper per kind of hand-off. The flags below are
 * load-bearing and easy to leave out of a second copy.
 */
private fun shareJsonIntent(
    context: Context,
    label: String,
    fileName: String,
    json: String,
): Intent {
    val uri = sharedRuleUri(context, fileName, json)

    val send = Intent(Intent.ACTION_SEND).apply {
        // The rule's own format, not text/plain: an app that can take a JSON
        // file says so, and a chooser full of apps that would mangle it is not
        // a useful chooser.
        type = "application/json"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TITLE, label)
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
