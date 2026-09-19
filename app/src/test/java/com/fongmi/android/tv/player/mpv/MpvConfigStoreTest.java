package com.fongmi.android.tv.player.mpv;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MpvConfigStoreTest {

    @Test
    public void readsTheLastExplicitAImageReaderBackend() {
        String config = "android-vulkan-aimagereader-backend=auto\n"
                + "# android-vulkan-aimagereader-backend=fragment\n"
                + "--android-vulkan-aimagereader-backend=direct # low power\n";
        assertEquals("direct", MpvConfigStore.findOptionValue(
                config, "android-vulkan-aimagereader-backend"));
    }

    @Test
    public void defaultConfig_allowsNativeAssScaling() {
        String config = MpvConfigStore.defaultConfig();

        assertTrue(config.contains("sub-ass-override=scale\n"));
        assertFalse(config.contains("sub-ass-override=yes\n"));
        // sub-font-provider **带前缀**：本包 libmpv 里没有 `sub-font-provider` 字面量，但也没有
        // `sub-font`/`sub-color`，而裸字段 `font`/`font-provider` 都在同一张 64 字节步长的字段表里
        // ⇒ mpv 的**子结构体**机制（全名 = 父组 + "-" + 字段，运行时拼；二进制里有 `%s-%s`）。
        // 已知对照：`sub-filter-sdh` 也只以裸字段 `sdh` 出现。⇒ 断言必须钉住**带前缀**的名字。
        assertTrue(config.contains("sub-font-provider=fontconfig\n"));
        assertFalse(config.contains("sub-font-provider=none\n"));
    }

    @Test
    public void parseProfilesJson_returnsEmptyForBrokenOrNonArrayJson() {
        assertTrue(MpvConfigStore.parseProfilesJson("broken").isEmpty());
        assertTrue(MpvConfigStore.parseProfilesJson("{\"id\":\"one\"}").isEmpty());
        assertTrue(MpvConfigStore.parseProfilesJson(null).isEmpty());
    }

    @Test
    public void parseProfilesJson_skipsNonObjectsAndWrongFieldTypes() {
        String json = "[null,1,\"bad\","
                + "{\"id\":{},\"name\":\"bad id\"},"
                + "{\"id\":\"bad-time\",\"time\":\"recent\"},"
                + "{\"id\":\"good\",\"name\":\"Remote\",\"type\":\"url\","
                + "\"source\":\"https://example.com/mpv.conf\",\"content\":null,\"time\":123}]";

        List<MpvConfigStore.ConfigProfile> profiles = MpvConfigStore.parseProfilesJson(json);

        assertEquals(1, profiles.size());
        assertEquals("good", profiles.get(0).id);
        assertEquals("Remote", profiles.get(0).name);
        assertEquals("url", profiles.get(0).type);
        assertEquals(123L, profiles.get(0).time);
    }

    @Test
    public void serializeProfiles_usesStableSchemaAndRoundTrips() {
        MpvConfigStore.ConfigProfile profile = new MpvConfigStore.ConfigProfile();
        profile.id = "profile-1";
        profile.name = "Cinema";
        profile.type = "text";
        profile.source = "";
        profile.content = "profile=fast";
        profile.time = 456L;
        profile.active = true;
        List<MpvConfigStore.ConfigProfile> input = new ArrayList<>();
        input.add(profile);

        String json = MpvConfigStore.serializeProfiles(input);
        List<MpvConfigStore.ConfigProfile> output = MpvConfigStore.parseProfilesJson(json);

        assertTrue(json.contains("\"id\":\"profile-1\""));
        assertTrue(json.contains("\"content\":\"profile=fast\""));
        assertTrue(!json.contains("active"));
        assertEquals(1, output.size());
        assertEquals("Cinema", output.get(0).name);
        assertEquals("profile=fast", output.get(0).content);
        assertEquals(456L, output.get(0).time);
    }
}
