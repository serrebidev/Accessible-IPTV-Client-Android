package dev.serrebi.accessibleiptv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportIssueTest {
	@Test fun issueLinkCarriesTitleBodyAndVersion() {
		val url = ReportIssue.url("Photo & video", "Line 1\nПривет", "Accessible IPTV Client 1.0 on Android 16")
		assertTrue(url, url.startsWith(
			"https://github.com/serrebidev/Accessible-IPTV-Client-Android/issues/new?title=Photo%20%26%20video&body=Line%201%0A%D0%9F"
		))
		assertTrue(url, url.endsWith("%0A---%0AAccessible%20IPTV%20Client%201.0%20on%20Android%2016"))
		assertFalse(url, url.any { it == ' ' || it == '\n' || it == '#' })
	}
}
