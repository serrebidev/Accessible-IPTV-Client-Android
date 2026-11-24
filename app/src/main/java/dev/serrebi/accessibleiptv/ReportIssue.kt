package dev.serrebi.accessibleiptv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import java.net.URLEncoder

/**
 * Report an issue on GitHub: the title and description are typed here, where TalkBack knows
 * the boxes, then GitHub's new-issue page opens with both filled in and the version line added.
 * GitHub needs the user's own account, so the last step, Create, is theirs in the browser.
 */
object ReportIssue {
	const val REPO = "serrebidev/Accessible-IPTV-Client-Android"

	fun show(activity: Activity) {
		val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0) }
		val summary = field(activity, col, activity.getString(R.string.report_issue_summary), multiline = false)
		val details = field(activity, col, activity.getString(R.string.report_issue_details), multiline = true)
		val dialog = AlertDialog.Builder(activity)
			.setTitle(R.string.report_issue)
			.setView(ScrollView(activity).apply { addView(col) })
			.setPositiveButton(android.R.string.ok) { _, _ ->
				val device = "Android ${Build.VERSION.RELEASE} ${Build.MANUFACTURER} ${Build.MODEL}"
				val url = url(summary.text.toString(), details.text.toString(), "Accessible IPTV Client ${BuildConfig.VERSION_NAME} on $device")
				runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
			}
			.setNegativeButton(android.R.string.cancel, null)
			.show()
		// No title, nothing to send: OK waits for one.
		val ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
		ok.isEnabled = false
		summary.doAfterTextChanged { ok.isEnabled = !it.isNullOrBlank() }
	}

	private fun field(activity: Activity, col: LinearLayout, label: String, multiline: Boolean): EditText {
		val box = EditText(activity).apply {
			id = View.generateViewId()
			inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
				(if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
			if (multiline) minLines = 4 else isSingleLine = true
		}
		col.addView(TextView(activity).apply { text = label; labelFor = box.id })
		col.addView(box)
		return box
	}

	/** GitHub's new-issue page with the title and body filled in. */
	fun url(title: String, details: String, appLine: String): String {
		val body = "${details.trim()}\n\n---\n$appLine"
		return "https://github.com/$REPO/issues/new?title=${encode(title.trim())}&body=${encode(body)}"
	}

	private fun encode(text: String) = URLEncoder.encode(text, "UTF-8").replace("+", "%20")
}
