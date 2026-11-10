package com.tianluoqaq.tlmnh.qa;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** The accepted fixed QA loading contract; this class has no world or scheduling operations. */
final class PreparationLoadingContract {

    static final int BORDER = 1, FRONTIER = 2;
    static final String REVISION = "native-lighting-halo2-v1";
    static final String REPORT = "78ed86830c34abbd4b11cb069ce0bbec57eb2c5475d99713de008d4f88a868d0";
    static final String DESIGN = "3a703a883e3cf1914e2375fa33f5a9a6c57665463805e52efdebdff5285bec7b";
    static final String CONTRACT = "683c42f3beb90559b64b483f1040a1d63687a585da39288403ac4d66e8764644";
    static final String LEDGER = "9a1aa451f9b7e275b7efd20dcfe868ee9f412d5f72d3f0eaecfe115e5e6f4a19";
    static final String ROOT = "82703b91ba2833c822def2abec4ed727aa3dfaa135ba6014436c9d13bcf35939";
    private static final String PINNED_CONTRACT_BASE64 = "ewogICJzY2hlbWEiOiAiZWZmZWN0LWVtcHR5LXdvcmxkLXByZXBhcmF0aW9uLWxvYWRpbmctY29udHJhY3QtdjEiLAogICJyZXZpc2lvbiI6ICJuYXRpdmUtbGlnaHRpbmctaGFsbzItdjEiLAogICJyb290QWNjZXB0YW5jZVJlcXVpcmVkIjogdHJ1ZSwKICAiYmFzZU1ldGhvZFNoYTI1NiI6ICI1OTJlNTQ4MjIxOGZjM2NlNmI0NmMxNmNmZmYzNGFiNjk1MDgyN2MwNGM5MmVlMDQxZjRmMzJjNWNkZWM0MWVhIiwKICAid29ybGRBdXRoUmV2aXNpb24iOiAic3Bhd24tYW5jaG9yZWQtcHJlbG9hZC12MSIsCiAgIndvcmxkQXV0aERlc2lnblNoYTI1NiI6ICI2NjY1MjgxYmZjYTJiMjBiNjMyMzhmMWNhMDBlYzhhYWI3YWM4NWIzOThhZGM0ZTI2NjExMjhhZmFiNzZiN2I5IiwKICAid29ybGRBdXRoUm9vdEFjY2VwdGFuY2VTaGEyNTYiOiAiZTRhMjhkMDA5ZTBiNTUxNDkyNjY0OTM0MTJmMTVkN2ZiNmQ2YWQyNWNiMjQ1MmNmM2VmMjczZWFkNTk2ZGRhMSIsCiAgIndvcmxkQXV0aE93bmVyQ2xhcmlmaWNhdGlvblNoYTI1NiI6ICI2NDViOWM3Y2ExZjI3Y2E2MTc2OTNmMGUyMjNjZmIyNWRhOTk1MjRjN2VjYjY2NGNkNmFmNmZlMDUxMWZmY2YxIiwKICAic291cmNlQmFzZWxpbmVSZWNlaXB0U2hhMjU2IjogIjI2ODEyN2U3OTVkMWY2NWJjNGVhYmI1ZGZlM2IyNzhlNDNkMTFmYzQ1ZWEwNTc0N2UzNzAzZjE2YTNkMWNkNDAiLAogICJwcmV2aW91c0RyaXZlclNoYTI1NiI6ICJiMTg0ODAwZmEzNTYxYzI0MjJmMmIwMzljYjc1NTI4YWVjMTlmMTg0NGVlNjI5N2JiNjM4MzBiNDQyNGFlYTdkIiwKICAicHJldmlvdXNMb2FkaW5nRnJvbnRpZXJDaHVua3MiOiAxLAogICJwcmVwYXJhdGlvbkJvcmRlckNodW5rcyI6IDEsCiAgImxvYWRpbmdGcm9udGllckNodW5rcyI6IDIsCiAgImZpeGVkTWVjaGFuaXNtQ2h1bmtzIjogewogICAgIm1pblgiOiA2MjUwLAogICAgIm1pbloiOiA2MjUwLAogICAgIm1heFgiOiA2MjUzLAogICAgIm1heFoiOiA2MjUzCiAgfSwKICAiZGVjbGFyZWRIcWFHcmlkT3JpZ2luIjogewogICAgIngiOiAwLAogICAgInkiOiAxMDAsCiAgICAieiI6IDAKICB9LAogICJjb29yZGluYXRlU2VtYW50aWNzIjogImluY2x1c2l2ZSBpbnRlZ2VyIGJvdW5kczsgSmF2YSBzaWduZWQgYXJpdGhtZXRpYyBzaGlmdCA+PjQ7IGZpeGVkIHJlY3RhbmdsZXMsIG5vIGFkYXB0aXZlIGV4cGFuc2lvbiIsCiAgIm1vZGVzIjogewogICAgIk4iOiB7CiAgICAgICJjYWxsZXIiOiAiRWZmZWN0RW1wdHlXb3JsZENhbmFyeS5uYXRpdmVEaXNwYXRjaGVyQ29sZFN0YXJ0TWVjaGFuaXNtIiwKICAgICAgImRpbWVuc2lvbiI6IDAsCiAgICAgICJleHBlY3RlZFByZXBhcmVBcmVhQ2FsbHMiOiAxLAogICAgICAiY2FsbGVyQmxvY2tCb3VuZHMiOiB7CiAgICAgICAgIm1pblgiOiAxMDAwMDAsCiAgICAgICAgIm1pbloiOiAxMDAwMDAsCiAgICAgICAgIm1heFgiOiAxMDAwNjMsCiAgICAgICAgIm1heFoiOiAxMDAwNjMKICAgICAgfSwKICAgICAgInJlcXVpcmVkQ2h1bmtzIjogewogICAgICAgICJtaW5YIjogNjI0OSwKICAgICAgICAibWluWiI6IDYyNDksCiAgICAgICAgIm1heFgiOiA2MjU0LAogICAgICAgICJtYXhaIjogNjI1NCwKICAgICAgICAiY291bnQiOiAzNgogICAgICB9LAogICAgICAicHJldmlvdXNSZXF1ZXN0ZWRGb3JjZWRDaHVua3MiOiB7CiAgICAgICAgIm1pblgiOiA2MjQ4LAogICAgICAgICJtaW5aIjogNjI0OCwKICAgICAgICAibWF4WCI6IDYyNTUsCiAgICAgICAgIm1heFoiOiA2MjU1LAogICAgICAgICJjb3VudCI6IDY0CiAgICAgIH0sCiAgICAgICJyZXF1ZXN0ZWRGb3JjZWRDaHVua3MiOiB7CiAgICAgICAgIm1pblgiOiA2MjQ3LAogICAgICAgICJtaW5aIjogNjI0NywKICAgICAgICAibWF4WCI6IDYyNTYsCiAgICAgICAgIm1heFoiOiA2MjU2LAogICAgICAgICJjb3VudCI6IDEwMAogICAgICB9LAogICAgICAiYWRkaXRpb25hbFJlcXVlc3RlZENodW5rQ291bnQiOiAzNiwKICAgICAgInN0cmljdEJsb2NrQm91bmRzIjogewogICAgICAgICJtaW5YIjogOTk5NTIsCiAgICAgICAgIm1pblkiOiAwLAogICAgICAgICJtaW5aIjogOTk5NTIsCiAgICAgICAgIm1heFgiOiAxMDAxMTEsCiAgICAgICAgIm1heFkiOiAyNTUsCiAgICAgICAgIm1heFoiOiAxMDAxMTEKICAgICAgfQogICAgfSwKICAgICJQIjogewogICAgICAiY2FsbGVyIjogIkVmZmVjdEVtcHR5V29ybGRDYW5hcnkubmF0aXZlRGlzcGF0Y2hlckNvbGRTdGFydE1lY2hhbmlzbSIsCiAgICAgICJkaW1lbnNpb24iOiAwLAogICAgICAiZXhwZWN0ZWRQcmVwYXJlQXJlYUNhbGxzIjogMSwKICAgICAgImNhbGxlckJsb2NrQm91bmRzIjogewogICAgICAgICJtaW5YIjogMTAwMDAwLAogICAgICAgICJtaW5aIjogMTAwMDAwLAogICAgICAgICJtYXhYIjogMTAwMDYzLAogICAgICAgICJtYXhaIjogMTAwMDYzCiAgICAgIH0sCiAgICAgICJyZXF1aXJlZENodW5rcyI6IHsKICAgICAgICAibWluWCI6IDYyNDksCiAgICAgICAgIm1pbloiOiA2MjQ5LAogICAgICAgICJtYXhYIjogNjI1NCwKICAgICAgICAibWF4WiI6IDYyNTQsCiAgICAgICAgImNvdW50IjogMzYKICAgICAgfSwKICAgICAgInByZXZpb3VzUmVxdWVzdGVkRm9yY2VkQ2h1bmtzIjogewogICAgICAgICJtaW5YIjogNjI0OCwKICAgICAgICAibWluWiI6IDYyNDgsCiAgICAgICAgIm1heFgiOiA2MjU1LAogICAgICAgICJtYXhaIjogNjI1NSwKICAgICAgICAiY291bnQiOiA2NAogICAgICB9LAogICAgICAicmVxdWVzdGVkRm9yY2VkQ2h1bmtzIjogewogICAgICAgICJtaW5YIjogNjI0NywKICAgICAgICAibWluWiI6IDYyNDcsCiAgICAgICAgIm1heFgiOiA2MjU2LAogICAgICAgICJtYXhaIjogNjI1NiwKICAgICAgICAiY291bnQiOiAxMDAKICAgICAgfSwKICAgICAgImFkZGl0aW9uYWxSZXF1ZXN0ZWRDaHVua0NvdW50IjogMzYsCiAgICAgICJzdHJpY3RCbG9ja0JvdW5kcyI6IHsKICAgICAgICAibWluWCI6IDk5OTUyLAogICAgICAgICJtaW5ZIjogMCwKICAgICAgICAibWluWiI6IDk5OTUyLAogICAgICAgICJtYXhYIjogMTAwMTExLAogICAgICAgICJtYXhZIjogMjU1LAogICAgICAgICJtYXhaIjogMTAwMTExCiAgICAgIH0KICAgIH0sCiAgICAiUlVOIjogewogICAgICAiY2FsbGVyIjogIlVuaWZpZWRDb21iYXRFZmZlY3RQaWxvdFRlc3RzLlJ1bm5lci5zdGFydCIsCiAgICAgICJkaW1lbnNpb24iOiAwLAogICAgICAiZXhwZWN0ZWRQcmVwYXJlQXJlYUNhbGxzIjogMSwKICAgICAgImNhbGxlckJsb2NrQm91bmRzIjogewogICAgICAgICJtaW5YIjogLTgwLAogICAgICAgICJtaW5aIjogLTgwLAogICAgICAgICJtYXhYIjogMTAwLAogICAgICAgICJtYXhaIjogOTAKICAgICAgfSwKICAgICAgInJlcXVpcmVkQ2h1bmtzIjogewogICAgICAgICJtaW5YIjogLTYsCiAgICAgICAgIm1pbloiOiAtNiwKICAgICAgICAibWF4WCI6IDcsCiAgICAgICAgIm1heFoiOiA2LAogICAgICAgICJjb3VudCI6IDE4MgogICAgICB9LAogICAgICAicHJldmlvdXNSZXF1ZXN0ZWRGb3JjZWRDaHVua3MiOiB7CiAgICAgICAgIm1pblgiOiAtNywKICAgICAgICAibWluWiI6IC03LAogICAgICAgICJtYXhYIjogOCwKICAgICAgICAibWF4WiI6IDcsCiAgICAgICAgImNvdW50IjogMjQwCiAgICAgIH0sCiAgICAgICJyZXF1ZXN0ZWRGb3JjZWRDaHVua3MiOiB7CiAgICAgICAgIm1pblgiOiAtOCwKICAgICAgICAibWluWiI6IC04LAogICAgICAgICJtYXhYIjogOSwKICAgICAgICAibWF4WiI6IDgsCiAgICAgICAgImNvdW50IjogMzA2CiAgICAgIH0sCiAgICAgICJhZGRpdGlvbmFsUmVxdWVzdGVkQ2h1bmtDb3VudCI6IDY2LAogICAgICAic3RyaWN0QmxvY2tCb3VuZHMiOiB7CiAgICAgICAgIm1pblgiOiAtMTI4LAogICAgICAgICJtaW5ZIjogMCwKICAgICAgICAibWluWiI6IC0xMjgsCiAgICAgICAgIm1heFgiOiAxNTksCiAgICAgICAgIm1heFkiOiAyNTUsCiAgICAgICAgIm1heFoiOiAxNDMKICAgICAgfQogICAgfQogIH0sCiAgInJlcXVlc3RlZFNjb3BlSXNXaG9sZVdvcmxkTG9hZGVkU2V0IjogZmFsc2UsCiAgIm5hdHVyYWxMb2FkaW5nT3V0c2lkZVJlcXVlc3RlZFNjb3BlIjogIm9ic2VydmUgd2l0aCBleGlzdGluZyB3b3JsZC9zb3VyY2UvcGhhc2UvZW50aXR5IGd1YXJkczsgbm8gc3VwcHJlc3Npb24gYW5kIG5vIG5ldyBleGVtcHRpb24iLAogICJzdXBwb3J0Q2h1bmtzQWRkZWRUb1JlcXVpcmVkIjogZmFsc2UsCiAgInN1cHBvcnRPdXRlckxpZ2h0T3JUZXJyYWluUmVhZGluZXNzR2F0ZUFkZGVkIjogZmFsc2UsCiAgInN1cHBvcnRVbmxvYWRWZXRvQWRkZWQiOiBmYWxzZSwKICAicmVxdWlyZWRTYW1lT2JqZWN0VGVycmFpbkFuZExpZ2h0IjogdHJ1ZSwKICAicHJlcGFyYXRpb25MaW1pdCI6IDEyMDAsCiAgInN0YWJsZVNhbXBsZXMiOiAyLAogICJldmVudExpbWl0IjogMjQwMDAsCiAgImVudGl0eUxpbWl0IjogMTAyNCwKICAic2VudGluZWxDYWxsYmFja0xpbWl0IjogMjQwMDAsCiAgImFsbERpbWVuc2lvblBvcHVsYXRpb25BbmRBZGRlZCI6IGZhbHNlLAogICJwb3B1bGF0aW9uRGltZW5zaW9uIjogMCwKICAicG9wdWxhdGlvblplcm9QcmVkaWNhdGVzIjogWwogICAgImdlblF1ZXVlIiwKICAgICJwb3BRdWV1ZSIsCiAgICAicGlwZWxpbmVCYWNrbG9nIiwKICAgICJwb3BDb3VudCIKICBdLAogICJuYXRpdmVBcnRpZmFjdHMiOiB7CiAgICAibW9kcy9hcmNoYWljZml4LTAuOC4yLmphciI6ICJjZjUxYjNhZjViOTQ4ZWM5NjNjOGRiZmIyNmQyNTM0MGJiYjY2YzcxZTJjODU3OTg0MTgzZGU3MThlM2UyZjQzIiwKICAgICJtb2RzL2hvcml6b25xYS0wLjE1LjAuamFyIjogImY2Njk1NTE3MGMzODNiMWVhMWRkNTgyMDdhYWEyOWZlZWIyNTc2MTU5YjJjMTBhMTBlNDgzZmRlMmUwN2E2MmQiCiAgfSwKICAibG9hZGluZ093bmVyIjogImV4aXN0aW5nIEdhbWVUZXN0Q2h1bmtMb2FkZXIuZm9yY2VDaHVua3NTdHJpY3QiLAogICJ0aWNrZXRPd25lciI6ICJIb3Jpem9uUUFNb2QuaW5zdGFuY2UiLAogICJ0aWNrZXRUeXBlIjogIkZvcmdlQ2h1bmtNYW5hZ2VyLlR5cGUuTk9STUFMIiwKICAidGlja2V0Q2FwYWNpdHlQb2xpY3kiOiAiZXhpc3RpbmcgcmVxdWVzdFRpY2tldFN0cmljdCBhbmQgbmF0aXZlIGdldENodW5rTGlzdERlcHRoIGJhdGNoaW5nOyBubyBjb25maWd1cmF0aW9uIG9yIGxpbWl0IG92ZXJyaWRlcyIsCiAgInJlbGVhc2VPd25lciI6IHsKICAgICJOIjogImV4aXN0aW5nIGhlbHBlci5hZnRlclRlc3QobG9hZGVyOjpyZWxlYXNlQWxsKSIsCiAgICAiUCI6ICJleGlzdGluZyBoZWxwZXIuYWZ0ZXJUZXN0KGxvYWRlcjo6cmVsZWFzZUFsbCkiLAogICAgIlJVTiI6ICJleGlzdGluZyBSdW5uZXIuY2xvc2UgLT4gY2h1bmtzLnJlbGVhc2VBbGwiCiAgfSwKICAibmF0aXZlUHJvZ3Jlc3NPd25lciI6ICJvcmRpbmFyeSBuYXRpdmUgd29ybGQvY2h1bmsgdGlja3M7IFFBIG9ubHkgcmVhZHMgcmVhZGluZXNzIiwKICAibm9OZXdDb250cm9scyI6IFsKICAgICJkaXJlY3QgTGlnaHRpbmdIb29rcyBvciBMaWdodGluZ0VuZ2luZSBjYWxscyIsCiAgICAibWFudWFsIGNodW5rIG9yIHdvcmxkIHRpY2tzIiwKICAgICJsaWdodGluZyBvciB0ZXJyYWluIGZsYWcgd3JpdGVzIiwKICAgICJtYW51YWwgc2NoZWR1bGVyIG9yIHByb2Nlc3NUaWNrIGNhbGxzIiwKICAgICJuZXcgYWN0b3IvcGxheWVyIHByZXBhcmF0aW9uIiwKICAgICJuZXcgdGlja2V0IHR5cGUgb3IgS2VlcExvYWRlZCBjb250cm9sIiwKICAgICJ3b3JsZCBvciBjaHVuayB1bmxvYWQgY2FuY2VsbGF0aW9uIiwKICAgICJuZXcgcG9wdWxhdGlvbiBzdXBwcmVzc2lvbiBzd2l0Y2hlcyIsCiAgICAiUk5HLCBOQlQsIHBoeXNpY3MsIHNlZWQsIHRpbWVvdXQgb3Igc3RhYmxlLXdpbmRvdyBjaGFuZ2VzIiwKICAgICJ1bmtub3duIGVudGl0eSBkZWxldGlvbiwgbW92ZW1lbnQgb3IgYWRvcHRpb24iCiAgXSwKICAiYmFzZU1ldGhvZEFuZFdvcmxkQXV0aEZpbGVzT3ZlcndyaXR0ZW4iOiBmYWxzZSwKICAiYmFzZWxpbmVSZWZyZWV6ZUFsbG93ZWQiOiBmYWxzZSwKICAib2xkU3VpdGVzTWF5UmVzdW1lIjogZmFsc2UsCiAgImNoYWluIjogWwogICAgIk4iLAogICAgIlAiLAogICAgIlJVTiIKICBdLAogICJzdWNjZXNzb3JBZnRlckZhaWxlZFByZWRlY2Vzc29yQWxsb3dlZCI6IGZhbHNlLAogICJvcmlnaW5hbFY2Q2FuYXJpZXNBbmRUd2VsdmVFeGFjdEdhdGVzUmVxdWlyZWQiOiB0cnVlLAogICJmdWxsUmVsZWFzZUFkbWlzc2lvbiI6IGZhbHNlLAogICJmaXhlZE1lY2hhbmlzbUNodW5rQ291bnQiOiAxNgp9Cg==";

    interface CheckedPath {

        Path check(Path path) throws Exception;
    }

    static void require(boolean value, String reason) {
        if (!value) throw new IllegalStateException(reason);
    }

    static Map<String, String> identity() {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("loadingRevision", REVISION);
        result.put("loadingDesignSha256", DESIGN);
        result.put("loadingContractSha256", CONTRACT);
        result.put("loadingRootAcceptanceSha256", ROOT);
        result.put("loadingSourceLedgerSha256", LEDGER);
        return result;
    }

    static Map<String, String> files() {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("report.md", REPORT);
        result.put("design.md", DESIGN);
        result.put("loading-contract.json", CONTRACT);
        result.put("source-ledger.json", LEDGER);
        result.put("root-review.json", ROOT);
        return result;
    }

    static String text(JsonObject object, String key) {
        return object.get(key)
            .getAsString();
    }

    static JsonObject parse(byte[] bytes) {
        return new JsonParser().parse(new String(bytes, StandardCharsets.UTF_8))
            .getAsJsonObject();
    }

    static String digest(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256")
            .digest(bytes)) result.append(String.format("%02x", value & 255));
        return result.toString();
    }

    static JsonObject contract() {
        return parse(
            Base64.getDecoder()
                .decode(PINNED_CONTRACT_BASE64));
    }

    static JsonObject plan(String mode) {
        require(mode.equals("N") || mode.equals("P") || mode.equals("RUN"), "Unreviewed loading mode");
        return contract().getAsJsonObject("modes")
            .getAsJsonObject(mode);
    }

    static void putIdentity(JsonObject target) {
        identity().forEach(target::addProperty);
    }

    static void verifyIdentity(JsonObject target) {
        identity()
            .forEach((key, value) -> require(value.equals(text(target, key)), "Loading identity differs: " + key));
    }

    static JsonObject verifyFiles(Path suite, JsonObject inputs, CheckedPath checked) throws Exception {
        require(
            inputs.entrySet()
                .size() == 5,
            "Loading supplement file set differs");
        Map<String, JsonObject> json = new LinkedHashMap<>();
        for (Map.Entry<String, String> pair : files().entrySet()) {
            JsonObject input = inputs.getAsJsonObject(pair.getKey());
            Path actual = checked.check(Paths.get(text(input, "path")));
            Path expected = checked.check(
                suite.resolve("empty-world-loading-inputs")
                    .resolve(pair.getKey()));
            require(
                actual.equals(expected) && Files.isRegularFile(actual, LinkOption.NOFOLLOW_LINKS)
                    && pair.getValue()
                        .equals(text(input, "sha256")),
                "Loading input path/declaration differs: " + pair.getKey());
            byte[] bytes = Files.readAllBytes(actual);
            require(
                pair.getValue()
                    .equals(digest(bytes)),
                "Actual loading file bytes differ: " + pair.getKey());
            String content = new String(bytes, StandardCharsets.UTF_8);
            require(
                !content.trim()
                    .isEmpty(),
                "Empty loading input");
            if (pair.getKey()
                .endsWith(".json")) json.put(pair.getKey(), parse(bytes));
        }
        JsonObject actual = json.get("loading-contract.json"), expected = contract();
        require(actual.equals(expected), "Parsed complete loading contract differs");
        JsonObject root = json.get("root-review.json");
        require(
            "root-native-lighting-halo-method-review-v1".equals(text(root, "schema"))
                && "ACCEPTED_FOR_PRIVATE_IMPLEMENTATION_INDEPENDENT_PEER_REQUIRED_BEFORE_PROMOTION"
                    .equals(text(root, "status"))
                && REVISION.equals(text(root, "revision"))
                && DESIGN.equals(text(root, "designSha256"))
                && REPORT.equals(text(root, "reportSha256"))
                && CONTRACT.equals(text(root, "contractSha256"))
                && LEDGER.equals(text(root, "sourceLedgerSha256"))
                && root.getAsJsonObject("loadingPlans")
                    .equals(actual.get("modes")),
            "Actual loading root acceptance differs");
        for (Map.Entry<String, String> pair : files().entrySet()) if (!pair.getKey()
            .equals("root-review.json"))
            require(
                pair.getValue()
                    .equals(text(root.getAsJsonObject("documents"), pair.getKey())),
                "Root document binding differs");
        JsonObject ledger = json.get("source-ledger.json");
        require(
            "empty-light-halo-design-source-ledger-v1".equals(text(ledger, "schema"))
                && ledger.getAsJsonArray("sources")
                    .size() == 42,
            "Loading source ledger differs");
        return actual;
    }

    static JsonObject checkedPlan(String mode, int minX, int minZ, int maxX, int maxZ, int border, int frontier) {
        JsonObject plan = plan(mode), caller = plan.getAsJsonObject("callerBlockBounds");
        require(border == BORDER && frontier == FRONTIER, "Fixed loading border/frontier differs");
        require(
            caller.get("minX")
                .getAsInt() == minX
                && caller.get("minZ")
                    .getAsInt() == minZ
                && caller.get("maxX")
                    .getAsInt() == maxX
                && caller.get("maxZ")
                    .getAsInt() == maxZ,
            "Actual loading caller differs");
        int x1 = (minX >> 4) - border, z1 = (minZ >> 4) - border, x2 = (maxX >> 4) + border, z2 = (maxZ >> 4) + border;
        require(
            rect(x1, z1, x2, z2).equals(plan.get("requiredChunks"))
                && rect(x1 - frontier, z1 - frontier, x2 + frontier, z2 + frontier)
                    .equals(plan.get("requestedForcedChunks")),
            "Actual required/forced loading bounds differ");
        JsonObject strict = plan.getAsJsonObject("strictBlockBounds");
        require(
            strict.get("minX")
                .getAsInt() == (x1 - frontier) * 16
                && strict.get("minZ")
                    .getAsInt() == (z1 - frontier) * 16
                && strict.get("maxX")
                    .getAsInt() == (x2 + frontier) * 16 + 15
                && strict.get("maxZ")
                    .getAsInt() == (z2 + frontier) * 16 + 15
                && strict.get("minY")
                    .getAsInt() == 0
                && strict.get("maxY")
                    .getAsInt() == 255,
            "Strict loading arguments differ");
        return plan;
    }

    private static JsonObject rect(int minX, int minZ, int maxX, int maxZ) {
        JsonObject result = new JsonObject();
        result.addProperty("minX", minX);
        result.addProperty("minZ", minZ);
        result.addProperty("maxX", maxX);
        result.addProperty("maxZ", maxZ);
        result.addProperty("count", (maxX - minX + 1) * (maxZ - minZ + 1));
        return result;
    }
}
