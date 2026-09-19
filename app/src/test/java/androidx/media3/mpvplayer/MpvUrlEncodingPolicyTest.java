package androidx.media3.mpvplayer;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class MpvUrlEncodingPolicyTest {

    @Test
    public void pureAsciiUrlIsReturnedUnchangedAndUncopied() {
        String url = "https://cdn.example.com/live/a.m3u8?token=abc&x=1";
        assertSame(url, MpvUrlEncodingPolicy.encodeForMpv(url));
    }

    @Test
    public void nullAndEmptyArePassedThrough() {
        assertNull(MpvUrlEncodingPolicy.encodeForMpv(null));
        assertEquals("", MpvUrlEncodingPolicy.encodeForMpv(""));
    }

    @Test
    public void chinesePathIsPercentEncodedAsUtf8() {
        assertEquals(
                "https://cdn.example.com/%E4%BA%A4%E9%94%8B/01.m3u8",
                MpvUrlEncodingPolicy.encodeForMpv("https://cdn.example.com/交锋/01.m3u8"));
    }

    @Test
    public void spaceAndControlCharactersAreEncoded() {
        assertEquals(
                "https://cdn.example.com/a%20b/c%0Ad.ts",
                MpvUrlEncodingPolicy.encodeForMpv("https://cdn.example.com/a b/c\nd.ts"));
    }

    @Test
    public void alreadyEncodedUrlIsNotDoubleEncoded() {
        String url = "https://cdn.example.com/%E4%BA%A4%E9%94%8B/01.m3u8";
        assertSame(url, MpvUrlEncodingPolicy.encodeForMpv(url));
    }

    /** 这条覆盖「首个待编码字节之前还夹着 `%`」的切片路径：字节下标必须仍等于字符下标。 */
    @Test
    public void encodedPrefixBeforeChineseIsPreservedVerbatim() {
        assertEquals(
                "https://cdn.example.com/c%20d/%E4%B8%AD%E6%96%87.m3u8",
                MpvUrlEncodingPolicy.encodeForMpv("https://cdn.example.com/c%20d/中文.m3u8"));
    }

    @Test
    public void urlLegalPunctuationIsLeftAlone() {
        String url = "https://u:p@host:8080/a/b?q=x&r=y#frag[0]~-_.,;!$'()*+=@";
        assertSame(url, MpvUrlEncodingPolicy.encodeForMpv(url));
    }

    @Test
    public void charactersCurlRejectsAreEncoded() {
        assertEquals(
                "https://h/a%22b%3Cc%3Ed%5Ce%5Ef%60g%7Bh%7Ci%7Dj",
                MpvUrlEncodingPolicy.encodeForMpv("https://h/a\"b<c>d\\e^f`g{h|i}j"));
    }

    @Test
    public void delAndNonAsciiBytesAreEncoded() {
        assertEquals(
                "https://h/a%7Fb%E2%82%AC",
                MpvUrlEncodingPolicy.encodeForMpv("https://h/a\u007Fb\u20AC"));
    }

    @Test
    public void chineseInQueryStringIsEncodedButSeparatorsAreKept() {
        assertEquals(
                "https://h/api?name=%E7%94%B5%E5%BD%B1&page=1",
                MpvUrlEncodingPolicy.encodeForMpv("https://h/api?name=电影&page=1"));
    }
}
