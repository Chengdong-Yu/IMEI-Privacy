package com.richard.imeiprivacy;

import org.junit.Test;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.EnumMap;

import static org.junit.Assert.*;

public class PrivacyRegressionTest {
    @Test public void missingProfileDoesNotReadRadioProperties() {
        List<String> reads = new ArrayList<>();
        IdentifierProfile profile = IdentifierProfile.load((key, fallback) -> {
            reads.add(key);
            assertTrue(key.startsWith("persist.sys.ipriv."));
            return fallback;
        });
        assertEquals(4, reads.size());
        assertEquals("358240051234565", profile.imei[0]);
        assertTrue(IdentifierProfile.isValidImei(profile.imei[1]));
    }

    @Test public void moduleProfileAndMeidAreConsistent() {
        Map<String, String> properties = new HashMap<>();
        properties.put("persist.sys.ipriv.imei0", "358240056543218");
        properties.put("persist.sys.ipriv.meid1", "a100000000abcd");
        IdentifierProfile profile = IdentifierProfile.load(properties::getOrDefault);
        assertEquals("358240056543218", profile.imei[0]);
        assertEquals("A100000000ABCD", profile.meid[1]);
    }

    @Test public void corruptProfileUsesSafeFallback() {
        IdentifierProfile profile = IdentifierProfile.load((key, fallback) -> "not-a-valid-id");
        assertEquals("358240051234565", profile.imei[0]);
        assertEquals("A1000000000002", profile.meid[1]);
        assertFalse(IdentifierProfile.isValidImei("358240051234564"));
        assertFalse(IdentifierProfile.isValidImei(null));
    }

    @Test public void matchesBothKnownDeviceInfoLayouts() {
        assertTrue(FindDeviceInfoSchema.matches(CnDeviceInfo.class, infoFields("b", "c", "d", "e", "f")));
        assertTrue(FindDeviceInfoSchema.matches(GlobalDeviceInfo.class, infoFields("b", "c", "d", "e", "f")));
    }

    @Test public void rejectsAliasAndUnrelatedClasses() {
        assertFalse(FindDeviceInfoSchema.matches(JadxAliases.class, infoFields("b", "c", "d", "e", "f")));
        assertFalse(FindDeviceInfoSchema.matches(Unrelated.class, infoFields("b", "c", "d", "e", "f")));
    }

    @Test public void renamedFieldsAreAcceptedOnlyWithADiscoveredMapping() {
        assertTrue(FindDeviceInfoSchema.matches(JadxAliases.class,
                infoFields("f5888b", "f5889c", "f5890d", "f5891e", "f")));
        assertFalse(FindDeviceInfoSchema.matches(CnDeviceInfo.class, infoFields("b", "b", "d", "e", "f")));
    }

    private Map<DexHookPlan.Role, String> infoFields(String... names) {
        Map<DexHookPlan.Role, String> result = new EnumMap<>(DexHookPlan.Role.class);
        for (int index = 0; index < names.length; index++) result.put(DexHookPlan.Role.values()[index], names[index]);
        return result;
    }

    private FindDeviceRequestSanitizer sanitizer() {
        return new FindDeviceRequestSanitizer(FindDevicePrivacyHook.requestReplacements());
    }

    @Test public void immutableMapIsCopiedAndAccountPreserved() {
        Map<String, String> input = new LinkedHashMap<>();
        input.put("cloudsp_fid", "fixture-original-fid");
        input.put("cloudsp_cpuId", "fixture-cpu");
        input.put("cloudsp_userId", "fixture-account");
        input.put("serviceToken", "fixture-token");
        input.put("signature", "fixture-signature");
        Map<Object, Object> result = sanitizer().copyMap(Collections.unmodifiableMap(input));
        assertNotEquals("fixture-original-fid", result.get("cloudsp_fid"));
        assertNotEquals("fixture-cpu", result.get("cloudsp_cpuId"));
        assertEquals("fixture-account", result.get("cloudsp_userId"));
        assertEquals("fixture-token", result.get("serviceToken"));
        assertEquals("fixture-signature", result.get("signature"));
        assertEquals("fixture-original-fid", input.get("cloudsp_fid"));
    }

    @Test public void newerOs4ReportFieldsAreMasked() {
        Map<String, String> input = new HashMap<>();
        for (String key : Arrays.asList("cloudsp_token", "cloudsp_hashEidList", "cloudsp_brief",
                "cloudsp_deviceLyraDid", "cloudsp_modelName", "cloudsp_marketName", "cloudsp_gpsInfo")) {
            input.put(key, "fixture-original");
        }
        Map<Object, Object> output = sanitizer().copyMap(input);
        for (String key : input.keySet()) assertNotEquals(key, "fixture-original", output.get(key));
    }

    @Test public void queryPreservesEncodedAccountBytesAndDuplicateKeys() {
        String url = "https://example.invalid/find?cloudsp_fid=old1&cloudsp_fid=old2"
                + "&serviceToken=a%2Bb%3D&cloudsp_userId=account+name#fragment";
        String output = sanitizer().url(url);
        assertFalse(output.contains("=old1"));
        assertFalse(output.contains("=old2"));
        assertTrue(output.contains("serviceToken=a%2Bb%3D&cloudsp_userId=account+name#fragment"));
        assertEquals(2, output.split("cloudsp_fid=", -1).length - 1);
    }

    @Test public void encodedSensitiveKeysAndMixedCaseAreHandled() {
        String result = sanitizer().form("cloudsp_%66id=old&CLOUDSP_SN=old&cUserId=keep");
        assertFalse(result.contains("=old"));
        assertTrue(result.endsWith("cUserId=keep"));
    }

    @Test public void lateAddedFidIsMaskedAndPolicyIsIdempotent() {
        Map<Object, Object> map = sanitizer().copyMap(Collections.singletonMap("serviceToken", "keep"));
        map.put("cloudsp_fid", "late-added-original");
        Map<Object, Object> once = sanitizer().copyMap(map);
        assertNotEquals("late-added-original", once.get("cloudsp_fid"));
        assertEquals(once, sanitizer().copyMap(once));
    }

    @Test public void nestedMapAndHeadersKeepTypes() {
        Map<Object, Object> source = new HashMap<>();
        source.put("Cookie", Arrays.asList("serviceToken=keep; userId=keep"));
        source.put("items", Collections.singletonList(Collections.singletonMap("imei", "fixture-original")));
        Map<Object, Object> output = sanitizer().copyMap(source);
        assertEquals(source.get("Cookie"), output.get("Cookie"));
        Map<?, ?> nested = (Map<?, ?>) ((List<?>) output.get("items")).get(0);
        assertNotEquals("fixture-original", nested.get("imei"));
    }

    @Test public void unrelatedAndEmptyQueriesStayUnchanged() {
        assertEquals("https://example.invalid/", sanitizer().url("https://example.invalid/"));
        assertEquals("https://example.invalid/?x=1&&", sanitizer().url("https://example.invalid/?x=1&&"));
        assertEquals("bad%=a%2B&userId=keep", sanitizer().form("bad%=a%2B&userId=keep"));
    }

    @Test public void nullHeadersDoNotChangePostBodyIndex() throws Exception {
        FindDeviceTransportLayout layout = FindDeviceTransportLayout.of(
                Transport.class.getDeclaredMethod("syncPost", String.class, Map.class, Object.class));
        assertNotNull(layout);
        assertEquals(0, layout.url);
        assertEquals(1, layout.headers);
        assertEquals(2, layout.body);
    }

    @Test public void asyncContextIsNotTreatedAsRequestBody() throws Exception {
        FindDeviceTransportLayout layout = FindDeviceTransportLayout.of(
                Transport.class.getDeclaredMethod("asyncGet", String.class, Runnable.class, Object.class));
        assertNotNull(layout);
        assertEquals(-1, layout.body);
        assertEquals(-1, layout.headers);
    }

    @Test public void finalSendAndUnknownOverloadsAreDistinguished() throws Exception {
        FindDeviceTransportLayout layout = FindDeviceTransportLayout.of(Transport.class.getDeclaredMethod(
                "asyncSend", String.class, String.class, Map.class, Object.class, Runnable.class));
        assertEquals(1, layout.url);
        assertEquals(2, layout.headers);
        assertEquals(3, layout.body);
        assertNull(FindDeviceTransportLayout.of(Transport.class.getDeclaredMethod("syncPost", int.class)));
    }

    @Test public void jsonBodiesMaskNestedIdentifiersWithoutChangingAccount() throws Exception {
        JSONObject input = new JSONObject("{\"cloudsp_userId\":\"keep\",\"serviceToken\":\"a+b=\","
                + "\"items\":[{\"cloudsp_sn\":\"original\",\"imsi\":\"original\"}]}");
        JSONObject output = (JSONObject) FindDeviceJsonSanitizer.copy(input, sanitizer());
        assertEquals("keep", output.getString("cloudsp_userId"));
        assertEquals("a+b=", output.getString("serviceToken"));
        assertNotEquals("original", output.getJSONArray("items").getJSONObject(0).getString("cloudsp_sn"));
        assertEquals("original", input.getJSONArray("items").getJSONObject(0).getString("cloudsp_sn"));
    }

    @Test public void jsonLocationAndSimCollectionsAreClearedWithoutChangingType() throws Exception {
        JSONObject input = new JSONObject().put("cloudsp_gpsInfo", new JSONObject().put("lat", 1))
                .put("cloudsp_phoneInfo", new JSONArray().put("fixture-sim"));
        JSONObject output = (JSONObject) FindDeviceJsonSanitizer.copy(input, sanitizer());
        assertEquals(0, output.getJSONObject("cloudsp_gpsInfo").length());
        assertEquals(0, output.getJSONArray("cloudsp_phoneInfo").length());
    }

    @Test public void mapsClearSimCollectionWithoutWrappingJsonAsAListElement() {
        Map<Object, Object> output = sanitizer().copyMap(Collections.singletonMap(
                "cloudsp_phoneInfo", Collections.singletonList("fixture-sim")));
        assertEquals(Collections.emptyList(), output.get("cloudsp_phoneInfo"));
    }

    enum DeviceType { PHONE }
    enum CredentialType { TZ }
    static class CnDeviceInfo {
        DeviceType a; String b, c, d, e, f; CredentialType g;
    }
    static class GlobalDeviceInfo { int a; String b, c, d, e, f; int g; }
    static class JadxAliases { int a; String f5888b, f5889c, f5890d, f5891e, f; int g; }
    static class Unrelated { static String a, b, c, d, e; }
    static class Transport {
        void syncPost(String url, Map<?, ?> headers, Object body) {}
        void asyncGet(String url, Runnable callback, Object context) {}
        void asyncSend(String method, String url, Map<?, ?> headers, Object body, Runnable callback) {}
        void syncPost(int unknown) {}
    }
}
