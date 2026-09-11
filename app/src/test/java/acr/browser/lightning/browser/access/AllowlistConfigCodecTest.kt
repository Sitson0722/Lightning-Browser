package acr.browser.lightning.browser.access

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Test
import java.util.Base64

class AllowlistConfigCodecTest {

    @Test
    fun `decodes configuration signed by documented Windows key`() {
        val file = validAllowlistConfig()

        assertThat(AllowlistConfigCodec.decode(file))
            .containsExactly("example.com", "www.school.edu.cn")
    }

    @Test
    fun `rejects a modified signed payload`() {
        val file = validAllowlistConfig()
        file[30] = (file[30].toInt() xor 1).toByte()

        assertThatThrownBy { AllowlistConfigCodec.decode(file) }
            .isInstanceOf(AllowlistConfigCodec.ConfigException.InvalidSignature::class.java)
    }
}

internal fun validAllowlistConfig(): ByteArray = Base64.getDecoder().decode(
    "TEJDRkcwMDEBAQIDBAUGBwgJCgsMAAAALT/XsDjkp0dlBmV/APZ/wSzXem4LsgpG6Pph" +
        "yWkWvDnWTZw5ZEnXBn64kIkT/wBIMEYCIQD7EjdbVEkHBQOIo0H3ypG/hg/xbf10Lg5" +
        "p7O5hLoVC3AIhAIunNvPVLr5I9CK4+LnK5VpXs5wvygi7Fe5jqeb2TQBR"
)
