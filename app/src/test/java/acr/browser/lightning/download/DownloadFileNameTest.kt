package acr.browser.lightning.download

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class DownloadFileNameTest {
    @Test
    fun `explicit markdown extension survives a binary MIME fallback`() {
        assertThat(resolve("notes.md", "attachment; filename=report.md", "bin"))
            .isEqualTo("report.md")
        assertThat(resolve("notes.md?token=123", null, "bin")).isEqualTo("notes.md")
        assertThat(resolve("notes.MD", null, "txt")).isEqualTo("notes.MD")
    }

    @Test
    fun `extended UTF8 filename takes precedence over ASCII fallback`() {
        assertThat(resolve("download", "attachment; filename=report.bin; filename*=UTF-8'zh'%E7%BB%93%E6%9E%9C.md", "bin"))
            .isEqualTo("结果.md")
    }

    @Test
    fun `encoded Chinese filenames work in both headers and URL paths`() {
        val encoded = "%E7%BB%93%E6%9E%9C.md"
        assertThat(resolve(encoded, null, "bin")).isEqualTo("结果.md")
        assertThat(resolve("download", "attachment; filename=\"$encoded\"", "bin"))
            .isEqualTo("结果.md")
        assertThat(resolve("download", "attachment; filename=\"结果.md\"", "bin"))
            .isEqualTo("结果.md")
    }

    @Test
    fun `spaces plus and literal percent encoding are decoded once`() {
        assertThat(resolve("a+b%20c%2520.md", null, "bin")).isEqualTo("a+b c%20.md")
        assertThat(resolve("download", "attachment; filename=\"a+b c.md\"", "bin"))
            .isEqualTo("a+b c.md")
    }

    @Test
    fun `quoted semicolons and case insensitive parameters are supported`() {
        assertThat(resolve("download", "attachment; FILENAME=\"a;b.md\"", "bin"))
            .isEqualTo("a;b.md")
    }

    @Test
    fun `unsupported charset or malformed encoding falls back without crashing`() {
        assertThat(resolve("fallback.md", "attachment; filename=notes.md; filename*=bogus''%FF", "bin"))
            .isEqualTo("notes.md")
        assertThat(resolve("bad%ZZ.md", null, "bin")).isEqualTo("bad%ZZ.md")
    }

    @Test
    fun `legacy Latin1 wrapped UTF8 is repaired while legitimate Latin1 stays intact`() {
        val garbled = String("结果.md".toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)
        assertThat(resolve("download", "attachment; filename=\"$garbled\"", "bin"))
            .isEqualTo("结果.md")
        assertThat(resolve("download", "attachment; filename=café.md", "bin"))
            .isEqualTo("café.md")
    }

    @Test
    fun `unsafe paths are flattened and unnamed files get a MIME extension`() {
        assertThat(resolve("download", "attachment; filename=\"../notes.md\"", "bin"))
            .isEqualTo("_notes.md")
        assertThat(resolve("", null, "md")).isEqualTo("download.md")
        assertThat(resolve("download", "attachment; filename=\"\"", "md"))
            .isEqualTo("download.md")
    }

    private fun resolve(path: String, disposition: String?, extension: String?) =
        DownloadFileName.resolve("https://example.org/$path", disposition, extension)
}
