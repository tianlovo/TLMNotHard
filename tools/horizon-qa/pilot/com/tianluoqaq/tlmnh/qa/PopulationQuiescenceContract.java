package com.tianluoqaq.tlmnh.qa;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.tianluoqaq.tlmnh.common.development.PackagedClassResourceResolver;

/** Exact, explicitly approved semantic overlay. No target-provider class resolution at bootstrap. */
public final class PopulationQuiescenceContract {

    public static final String REVISION = "native-population-quiescence-v1";
    public static final String CONTRACT = "a7aa34a26f86060949e1b41ece621f1a0a62f1faac4cbcd94cda48550414b0a2",
        DESIGN = "db574806be298900cb380a62535db8290925f59ef197a868a6829b3634a54fe7",
        APPROVAL = "7792bd67b3dcd00a5e73ccd8ae89c16f9542ec4591559742cc2ea16082d4bf24",
        LEDGER = "d6230df5d2977f9d8f7eaf287406488ad4a9a2371183e6a5f12e4159440fd471";
    public static final String CONFIG = "mixins.tlmnhqa.population-quiescence.json";
    private static final String PINNED = "ew0KICAic2NoZW1hIjogImVmZmVjdC1uYXRpdmUtcG9wdWxhdGlvbi1xdWllc2NlbmNlLWNvbnRyYWN0LXYxIiwNCiAgInJldmlzaW9uIjogIm5hdGl2ZS1wb3B1bGF0aW9uLXF1aWVzY2VuY2UtdjEiLA0KICAiZGVzaWduU2hhMjU2IjogImRiNTc0ODA2YmUyOTg5MDBjYjM4MGE2MjUzNWRiODI5MDkyNWY1OWVmMTk3YTg2OGE2ODI5YjM2MzRhNTRmZTciLA0KICAiYXBwcm92YWxTaGEyNTYiOiAiNzc5MmJkNjdiM2RjZDAwYTVlNzNjY2Q4YWU4OWMxNmY5NTQyZWM0NTkxNTU5NzQyY2MyZWExNjA4MmQ0YmYyNCIsDQogICJzb3VyY2VMZWRnZXJTaGEyNTYiOiAiZDYyMzBkZjVkMjk3N2Y5ZDhmN2VhZjI4NzQwNjQ4OGFkNGE5YTIzNzExODNlNmE1ZjEyZTQxNTk0NDBmZDQ3MSIsDQogICJiYXNlTWV0aG9kU2hhMjU2IjogIjU5MmU1NDgyMjE4ZmMzY2U2YjQ2YzE2Y2ZmZjM0YWI2OTUwODI3YzA0YzkyZWUwNDFmNGYzMmM1Y2RlYzQxZWEiLA0KICAid29ybGRBdXRoUmV2aXNpb24iOiAic3Bhd24tYW5jaG9yZWQtcHJlbG9hZC12MSIsDQogICJ3b3JsZEF1dGhEZXNpZ25TaGEyNTYiOiAiNjY2NTI4MWJmY2EyYjIwYjYzMjM4ZjFjYTAwZWM4YWFiN2FjODViMzk4YWRjNGUyNjYxMTI4YWZhYjc2YjdiOSIsDQogICJ3b3JsZEF1dGhSb290QWNjZXB0YW5jZVNoYTI1NiI6ICJlNGEyOGQwMDllMGI1NTE0OTI2NjQ5MzQxMmYxNWQ3ZmI2ZDZhZDI1Y2IyNDUyY2YzZWYyNzNlYWQ1OTZkZGExIiwNCiAgIndvcmxkQXV0aE93bmVyQ2xhcmlmaWNhdGlvblNoYTI1NiI6ICI2NDViOWM3Y2ExZjI3Y2E2MTc2OTNmMGUyMjNjZmIyNWRhOTk1MjRjN2VjYjY2NGNkNmFmNmZlMDUxMWZmY2YxIiwNCiAgInNvdXJjZUJhc2VsaW5lUmVjZWlwdFNoYTI1NiI6ICIyNjgxMjdlNzk1ZDFmNjViYzRlYWJiNWRmZTNiMjc4ZTQzZDExZmM0NWVhMDU3NDdlMzcwM2YxNmEzZDFjZDQwIiwNCiAgImhhbG8yQ29udHJhY3RTaGEyNTYiOiAiNjgzYzQyZjNiZWI5MDU1OWI2NGI0ODNmMTA0MGExZDYzNjg3YTU4NWRhMzkyODg0MDNhYzRkNjZlODc2NDY0NCIsDQogICJoYWxvMlJvb3RBY2NlcHRhbmNlU2hhMjU2IjogIjgyNzAzYjkxYmEyODMzYzgyMmRlZjJhYmVjNGVkNzI3YWEzZGZhYTEzNWJhNjAxNDQzNmM5ZDEzYmNmMzU5MzkiLA0KICAib3ZlcnJpZGRlblplcm9QcmVkaWNhdGVzIjogWw0KICAgICJwb3BRdWV1ZSIsDQogICAgInBpcGVsaW5lQmFja2xvZyINCiAgXSwNCiAgInJldGFpbmVkWmVyb1ByZWRpY2F0ZXMiOiBbDQogICAgImdlblF1ZXVlIiwNCiAgICAicG9wQ291bnQiDQogIF0sDQogICJhZGRpdGlvbmFsWmVyb1ByZWRpY2F0ZXMiOiBbDQogICAgInRlcnJhaW5HZW5Db3VudCIsDQogICAgIm5laWdoYm9yRmlsbHMiLA0KICAgICJwb3B1bGF0aW9uRGVwdGgiDQogIF0sDQogICJwb3B1bGF0aW9uRGltZW5zaW9uIjogMCwNCiAgIm93bmVyUnVsZSI6ICJvbmUgZGltMCByZWdpc3RlcmVkIHdvcmxkL3Byb3ZpZGVyL2Nwcy9zY2hlZHVsZXIvc3RhdHMvc2VydmVyVGhyZWFkOyBubyByZXBsYWNlbWVudDsgb3RoZXIgZGltZW5zaW9ucyBuZXZlciBpbnZhbGlkYXRlIG93bmVyIiwNCiAgInB1YmxpY2F0aW9uUnVsZSI6ICJuYXR1cmFsIHByb2Nlc3NUaWNrIEhFQUQgaW52YWxpZGF0ZXM7IG91dGVyIHN1Y2Nlc3NmdWwgUkVUVVJOIG9mIGN1cnJlbnQgc2VydmVyL3dvcmxkIHRpY2sgcHVibGlzaGVzIGZpbmFsIG93bmVyIGdlbmVyYXRpb247IHNldmVuIG11dGF0aW9ucyBhbmQgZ2xvYmFsIGNsZWFyIGludmFsaWRhdGUiLA0KICAidG9wb2xvZ3lSdWxlIjogImNvbXBsZXRlIHB1YmxpYyBsb2FkZWQgbGlzdC9tYXAgYmlqZWN0aW9uOyBhbGwgdW5wb3B1bGF0ZWQgY2VudGVycyBtaXNzID49MSBvZiBlaWdodCBuZWlnaGJvcnMgYW5kIGFyZSBvdXRzaWRlIGV2ZXJ5IGFjdHVhbCBwbGF5ZXIgc3F1YXJlKHZpZXdEaXN0YW5jZSsxKTsgbm8gcXVldWUtbWVtYmVyIGNvdW50IHBhaXJpbmciLA0KICAibmF0aXZlQXJ0aWZhY3RzIjogew0KICAgICJtb2RzL2hvZGdlcG9kZ2UtMi43LjIyMS5qYXIiOiB7DQogICAgICAic2hhMjU2IjogIjliMGJmYjdlODRhNjYzODczMTk5NTJkNzZlNmYxMTRkNjIxOGE5ODhlMDRkMzAzOTY1YzMxZWZiMjE2NWU4MjQiLA0KICAgICAgImVudHJpZXMiOiB7DQogICAgICAgICJjb20vbWl0Y2hlajEyMy9ob2RnZXBvZGdlL21peGlucy9ob29rcy9DaHVua0dlblNjaGVkdWxlci5jbGFzcyI6ICIzM2Y0ZmQxNTAxZDkxNjdlYWY2NjRjMjc3ZDQ5MjU4OTA1OTBjNTQwYWRlMjEwYmE2ZmMwMGM4YzJmNTEwYmUxIiwNCiAgICAgICAgImNvbS9taXRjaGVqMTIzL2hvZGdlcG9kZ2UvbWl4aW5zL2hvb2tzL0NodW5rR2VuU3RhdHMuY2xhc3MiOiAiODgwY2Q1MWFmZWE5N2JlNmQyMDViMjhiZmVlNzQ3ZDgzNmNiYzA2NTJlMjJhZTc0Zjg0NjIyYWEzZTA3ODU3NiINCiAgICAgIH0NCiAgICB9LA0KICAgICJtb2RzLyt1bmltaXhpbnMtYWxsLTEuNy4xMC0wLjMuMS5qYXIiOiB7DQogICAgICAic2hhMjU2IjogImFkMGVhNGY5MmRhZjdiZjdlYzVjMTBlMTYyNTg0MjVlNDc5MjkxMjY5NTRhZWE1ZDgwZGY2OWNlMjZjZDczMTgiLA0KICAgICAgImVudHJpZXMiOiB7DQogICAgICAgICJvcmcvc3BvbmdlcG93ZXJlZC9hc20vc2VydmljZS9tb2phbmcvTWl4aW5TZXJ2aWNlTGF1bmNoV3JhcHBlci5jbGFzcyI6ICI3Y2FmZWRkMDczMTFmMjA3OWM4ZDMwMzgwMTQyMzQ5N2Y1ZGZiZDI4MzM1ODA2ZTEyNzk4NDJiZDYyZGI4ODJiIiwNCiAgICAgICAgIm9yZy9zcG9uZ2Vwb3dlcmVkL2FzbS9sYXVuY2gvcGxhdGZvcm0vTWl4aW5QbGF0Zm9ybUFnZW50RGVmYXVsdC5jbGFzcyI6ICI5YzlhODMxZjk5YTk1MGQ3OTdmM2QyN2FkYmQzOTNmNmIxZGYzZWQ4ZDAxYWUxNzJkZTVlMDI0ZWI5ZDExZDgwIiwNCiAgICAgICAgIm9yZy9zcG9uZ2Vwb3dlcmVkL2FzbS9sYXVuY2gvcGxhdGZvcm0vTWl4aW5QbGF0Zm9ybUFnZW50Rk1MTGVnYWN5LmNsYXNzIjogImU2YzI4MWNmM2ZlNWM3ZjYwNzMwNjEwNTAyODNkODA1ZWE2ZDZhYTY2NzY1YTI4MjIwZTJlODAzMGI5YmFmMjQiDQogICAgICB9DQogICAgfQ0KICB9LA0KICAicWFNYW5pZmVzdCI6IHsNCiAgICAiVHdlYWtDbGFzcyI6ICJvcmcuc3BvbmdlcG93ZXJlZC5hc20ubGF1bmNoLk1peGluVHdlYWtlciIsDQogICAgIkZvcmNlTG9hZEFzTW9kIjogInRydWUiLA0KICAgICJGTUxDb3JlUGx1Z2luQ29udGFpbnNGTUxNb2QiOiAidHJ1ZSIsDQogICAgIk1peGluQ29uZmlncyI6ICJtaXhpbnMudGxtbmhxYS5wb3B1bGF0aW9uLXF1aWVzY2VuY2UuanNvbiINCiAgfSwNCiAgInByb3BlcnR5IjogInRsbW5oLnFhLnBvcHVsYXRpb25RdWllc2NlbmNlIiwNCiAgImRlZmF1bHRPZmYiOiB0cnVlLA0KICAicHJlcGFyYXRpb25MaW1pdCI6IDEyMDAsDQogICJzdGFibGVTYW1wbGVzIjogMiwNCiAgImV2ZW50TGltaXQiOiAyNDAwMCwNCiAgImVudGl0eUxpbWl0IjogMTAyNCwNCiAgImxvYWRpbmdQbGFucyI6IHsNCiAgICAiTiI6IHsNCiAgICAgICJjYWxsZXIiOiAiRWZmZWN0RW1wdHlXb3JsZENhbmFyeS5uYXRpdmVEaXNwYXRjaGVyQ29sZFN0YXJ0TWVjaGFuaXNtIiwNCiAgICAgICJkaW1lbnNpb24iOiAwLA0KICAgICAgImV4cGVjdGVkUHJlcGFyZUFyZWFDYWxscyI6IDEsDQogICAgICAiY2FsbGVyQmxvY2tCb3VuZHMiOiB7DQogICAgICAgICJtaW5YIjogMTAwMDAwLA0KICAgICAgICAibWluWiI6IDEwMDAwMCwNCiAgICAgICAgIm1heFgiOiAxMDAwNjMsDQogICAgICAgICJtYXhaIjogMTAwMDYzDQogICAgICB9LA0KICAgICAgInJlcXVpcmVkQ2h1bmtzIjogew0KICAgICAgICAibWluWCI6IDYyNDksDQogICAgICAgICJtaW5aIjogNjI0OSwNCiAgICAgICAgIm1heFgiOiA2MjU0LA0KICAgICAgICAibWF4WiI6IDYyNTQsDQogICAgICAgICJjb3VudCI6IDM2DQogICAgICB9LA0KICAgICAgInByZXZpb3VzUmVxdWVzdGVkRm9yY2VkQ2h1bmtzIjogew0KICAgICAgICAibWluWCI6IDYyNDgsDQogICAgICAgICJtaW5aIjogNjI0OCwNCiAgICAgICAgIm1heFgiOiA2MjU1LA0KICAgICAgICAibWF4WiI6IDYyNTUsDQogICAgICAgICJjb3VudCI6IDY0DQogICAgICB9LA0KICAgICAgInJlcXVlc3RlZEZvcmNlZENodW5rcyI6IHsNCiAgICAgICAgIm1pblgiOiA2MjQ3LA0KICAgICAgICAibWluWiI6IDYyNDcsDQogICAgICAgICJtYXhYIjogNjI1NiwNCiAgICAgICAgIm1heFoiOiA2MjU2LA0KICAgICAgICAiY291bnQiOiAxMDANCiAgICAgIH0sDQogICAgICAiYWRkaXRpb25hbFJlcXVlc3RlZENodW5rQ291bnQiOiAzNiwNCiAgICAgICJzdHJpY3RCbG9ja0JvdW5kcyI6IHsNCiAgICAgICAgIm1pblgiOiA5OTk1MiwNCiAgICAgICAgIm1pblkiOiAwLA0KICAgICAgICAibWluWiI6IDk5OTUyLA0KICAgICAgICAibWF4WCI6IDEwMDExMSwNCiAgICAgICAgIm1heFkiOiAyNTUsDQogICAgICAgICJtYXhaIjogMTAwMTExDQogICAgICB9DQogICAgfSwNCiAgICAiUCI6IHsNCiAgICAgICJjYWxsZXIiOiAiRWZmZWN0RW1wdHlXb3JsZENhbmFyeS5uYXRpdmVEaXNwYXRjaGVyQ29sZFN0YXJ0TWVjaGFuaXNtIiwNCiAgICAgICJkaW1lbnNpb24iOiAwLA0KICAgICAgImV4cGVjdGVkUHJlcGFyZUFyZWFDYWxscyI6IDEsDQogICAgICAiY2FsbGVyQmxvY2tCb3VuZHMiOiB7DQogICAgICAgICJtaW5YIjogMTAwMDAwLA0KICAgICAgICAibWluWiI6IDEwMDAwMCwNCiAgICAgICAgIm1heFgiOiAxMDAwNjMsDQogICAgICAgICJtYXhaIjogMTAwMDYzDQogICAgICB9LA0KICAgICAgInJlcXVpcmVkQ2h1bmtzIjogew0KICAgICAgICAibWluWCI6IDYyNDksDQogICAgICAgICJtaW5aIjogNjI0OSwNCiAgICAgICAgIm1heFgiOiA2MjU0LA0KICAgICAgICAibWF4WiI6IDYyNTQsDQogICAgICAgICJjb3VudCI6IDM2DQogICAgICB9LA0KICAgICAgInByZXZpb3VzUmVxdWVzdGVkRm9yY2VkQ2h1bmtzIjogew0KICAgICAgICAibWluWCI6IDYyNDgsDQogICAgICAgICJtaW5aIjogNjI0OCwNCiAgICAgICAgIm1heFgiOiA2MjU1LA0KICAgICAgICAibWF4WiI6IDYyNTUsDQogICAgICAgICJjb3VudCI6IDY0DQogICAgICB9LA0KICAgICAgInJlcXVlc3RlZEZvcmNlZENodW5rcyI6IHsNCiAgICAgICAgIm1pblgiOiA2MjQ3LA0KICAgICAgICAibWluWiI6IDYyNDcsDQogICAgICAgICJtYXhYIjogNjI1NiwNCiAgICAgICAgIm1heFoiOiA2MjU2LA0KICAgICAgICAiY291bnQiOiAxMDANCiAgICAgIH0sDQogICAgICAiYWRkaXRpb25hbFJlcXVlc3RlZENodW5rQ291bnQiOiAzNiwNCiAgICAgICJzdHJpY3RCbG9ja0JvdW5kcyI6IHsNCiAgICAgICAgIm1pblgiOiA5OTk1MiwNCiAgICAgICAgIm1pblkiOiAwLA0KICAgICAgICAibWluWiI6IDk5OTUyLA0KICAgICAgICAibWF4WCI6IDEwMDExMSwNCiAgICAgICAgIm1heFkiOiAyNTUsDQogICAgICAgICJtYXhaIjogMTAwMTExDQogICAgICB9DQogICAgfSwNCiAgICAiUlVOIjogew0KICAgICAgImNhbGxlciI6ICJVbmlmaWVkQ29tYmF0RWZmZWN0UGlsb3RUZXN0cy5SdW5uZXIuc3RhcnQiLA0KICAgICAgImRpbWVuc2lvbiI6IDAsDQogICAgICAiZXhwZWN0ZWRQcmVwYXJlQXJlYUNhbGxzIjogMSwNCiAgICAgICJjYWxsZXJCbG9ja0JvdW5kcyI6IHsNCiAgICAgICAgIm1pblgiOiAtODAsDQogICAgICAgICJtaW5aIjogLTgwLA0KICAgICAgICAibWF4WCI6IDEwMCwNCiAgICAgICAgIm1heFoiOiA5MA0KICAgICAgfSwNCiAgICAgICJyZXF1aXJlZENodW5rcyI6IHsNCiAgICAgICAgIm1pblgiOiAtNiwNCiAgICAgICAgIm1pbloiOiAtNiwNCiAgICAgICAgIm1heFgiOiA3LA0KICAgICAgICAibWF4WiI6IDYsDQogICAgICAgICJjb3VudCI6IDE4Mg0KICAgICAgfSwNCiAgICAgICJwcmV2aW91c1JlcXVlc3RlZEZvcmNlZENodW5rcyI6IHsNCiAgICAgICAgIm1pblgiOiAtNywNCiAgICAgICAgIm1pbloiOiAtNywNCiAgICAgICAgIm1heFgiOiA4LA0KICAgICAgICAibWF4WiI6IDcsDQogICAgICAgICJjb3VudCI6IDI0MA0KICAgICAgfSwNCiAgICAgICJyZXF1ZXN0ZWRGb3JjZWRDaHVua3MiOiB7DQogICAgICAgICJtaW5YIjogLTgsDQogICAgICAgICJtaW5aIjogLTgsDQogICAgICAgICJtYXhYIjogOSwNCiAgICAgICAgIm1heFoiOiA4LA0KICAgICAgICAiY291bnQiOiAzMDYNCiAgICAgIH0sDQogICAgICAiYWRkaXRpb25hbFJlcXVlc3RlZENodW5rQ291bnQiOiA2NiwNCiAgICAgICJzdHJpY3RCbG9ja0JvdW5kcyI6IHsNCiAgICAgICAgIm1pblgiOiAtMTI4LA0KICAgICAgICAibWluWSI6IDAsDQogICAgICAgICJtaW5aIjogLTEyOCwNCiAgICAgICAgIm1heFgiOiAxNTksDQogICAgICAgICJtYXhZIjogMjU1LA0KICAgICAgICAibWF4WiI6IDE0Mw0KICAgICAgfQ0KICAgIH0NCiAgfSwNCiAgIndob2xlV29ybGRBbmRVbmtub3duUHJlc2VydmVkIjogdHJ1ZSwNCiAgIndvcmxkQ29udHJvbHNBZGRlZCI6IGZhbHNlLA0KICAicHJpdmF0ZVF1ZXVlT3JJT1Byb29mIjogZmFsc2UsDQogICJhbGxEaW1lbnNpb25Qb3B1bGF0aW9uQW5kQWRkZWQiOiBmYWxzZSwNCiAgInNvdXJjZVNjb3BlIjogInBhY2thZ2VkIHBoeXNpY2FsIGVudHJ5IGFuZCBzYW1lIENvZGVTb3VyY2U7IGZpbmFsIHRyYW5zZm9ybWVkIGRlZmluaXRpb24gbm90IHZlcmlmaWVkIg0KfQ0K";
    private static final Map<String, String> INPUTS = new LinkedHashMap<>();
    private static final Map<Class<?>, JsonObject> SOURCES = new IdentityHashMap<>();
    private static volatile boolean selected;
    private static volatile String selectionFailure;
    private static JsonObject launch;
    static {
        INPUTS.put("approval.json", "7792bd67b3dcd00a5e73ccd8ae89c16f9542ec4591559742cc2ea16082d4bf24");
        INPUTS.put(
            "contract-derivation-receipt.json",
            "364a367d1e712d92845ad5bf519d12e35643221d87698c221e56b2f2e621ad73");
        INPUTS.put("design-v2.md", "db574806be298900cb380a62535db8290925f59ef197a868a6829b3634a54fe7");
        INPUTS.put("quiescence-contract.json", "a7aa34a26f86060949e1b41ece621f1a0a62f1faac4cbcd94cda48550414b0a2");
        INPUTS.put("report.md", "e6f823d4a2d15cc975706573a5b94c444783b3023d664eeace82512c799e4a24");
        INPUTS.put("source-ledger.json", "d6230df5d2977f9d8f7eaf287406488ad4a9a2371183e6a5f12e4159440fd471");
    }

    private PopulationQuiescenceContract() {}

    public static boolean requested() {
        return REVISION.equals(System.getProperty("tlmnh.qa.populationQuiescence", ""));
    }

    public static JsonObject identity() {
        JsonObject j = new JsonObject();
        j.addProperty("revision", REVISION);
        j.addProperty("contractSha256", CONTRACT);
        j.addProperty("designSha256", DESIGN);
        j.addProperty("approvalSha256", APPROVAL);
        j.addProperty("sourceLedgerSha256", LEDGER);
        return j;
    }

    public static JsonObject pinned() {
        return parse(
            new String(
                Base64.getDecoder()
                    .decode(PINNED),
                StandardCharsets.UTF_8));
    }

    public static String text(JsonObject j, String k) {
        return j.get(k)
            .getAsString();
    }

    public static void require(boolean b, String s) {
        if (!b) throw new IllegalStateException(s);
    }

    private static JsonObject parse(String s) {
        return new JsonParser().parse(s)
            .getAsJsonObject();
    }

    private static Path checked(Path p) throws Exception {
        p = p.toAbsolutePath()
            .normalize();
        for (Path a = p; a != null; a = a.getParent()) {
            require(!Files.isSymbolicLink(a), "Quiescence symbolic input");
            if (Files.exists(a, LinkOption.NOFOLLOW_LINKS)) require(
                !Files.readAttributes(a, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                    .isOther(),
                "Quiescence reparse input");
        }
        require(p.equals(p.toRealPath()), "Quiescence canonical input differs");
        return p;
    }

    public static String sha(Path p) throws Exception {
        StringBuilder b = new StringBuilder();
        for (byte v : MessageDigest.getInstance("SHA-256")
            .digest(Files.readAllBytes(p))) b.append(String.format("%02x", v & 255));
        return b.toString();
    }

    public static void verifyDeclaration(JsonObject declaration, Path suite) throws Exception {
        require(identity().equals(declaration.get("quiescenceIdentity")), "Quiescence identity differs");
        JsonObject inputs = declaration.getAsJsonObject("quiescenceInputs");
        require(
            inputs.entrySet()
                .size() == INPUTS.size(),
            "Quiescence input count differs");
        JsonObject parsed = new JsonObject();
        Path directory = checked(suite.resolve("empty-world-quiescence-inputs"));
        for (Map.Entry<String, String> e : INPUTS.entrySet()) {
            JsonObject row = inputs.getAsJsonObject(e.getKey());
            Path path = checked(Paths.get(text(row, "path")));
            require(
                path.equals(directory.resolve(e.getKey())) && text(row, "sha256").equals(e.getValue())
                    && sha(path).equals(e.getValue()),
                "Actual quiescence input drift: " + e.getKey());
            if (e.getKey()
                .endsWith(".json"))
                parsed.add(e.getKey(), parse(new String(Files.readAllBytes(path), StandardCharsets.UTF_8)));
        }
        require(pinned().equals(parsed.get("quiescence-contract.json")), "Actual overlay contract differs");
        JsonObject approved = parsed.getAsJsonObject("approval.json");
        require(
            approved.get("approved")
                .getAsBoolean()
                && text(approved.getAsJsonObject("documents"), "调研报告.md").equals(INPUTS.get("report.md"))
                && text(approved.getAsJsonObject("documents"), "架构设计-v2.md").equals(DESIGN),
            "Explicit approval documents differ");
        JsonObject derivation = parsed.getAsJsonObject("contract-derivation-receipt.json");
        require(
            text(derivation, "contractSha256").equals(CONTRACT) && text(derivation, "approvalSha256").equals(APPROVAL)
                && !derivation.get("rootImplementationAcceptanceClaimed")
                    .getAsBoolean(),
            "Derived contract authorization differs");
    }

    public static synchronized void select() throws Exception {
        require(requested(), "Quiescence is default off");
        Path manifest = checked(Paths.get(System.getProperty("tlmnh.qaEffectEmptyWorldManifest", "")));
        require(
            sha(manifest).equals(System.getProperty("tlmnh.qaEffectEmptyWorldManifestSha256", "")),
            "Quiescence launch manifest drift");
        JsonObject j = parse(new String(Files.readAllBytes(manifest), StandardCharsets.UTF_8));
        verifyDeclaration(
            j,
            manifest.getParent()
                .getParent());
        verifyProperties(j);
        Path root = checked(Paths.get(text(j, "instanceRoot")));
        JsonObject natives = pinned().getAsJsonObject("nativeArtifacts");
        for (Map.Entry<String, JsonElement> e : natives.entrySet()) {
            Path p = checked(root.resolve(e.getKey()));
            JsonObject n = e.getValue()
                .getAsJsonObject();
            require(
                sha(p).equals(text(n, "sha256"))
                    && text(j.getAsJsonObject("files"), e.getKey()).equals(text(n, "sha256")),
                "Quiescence physical native artifact drift");
            try (JarFile z = new JarFile(p.toFile())) {
                for (Map.Entry<String, JsonElement> entry : n.getAsJsonObject("entries")
                    .entrySet()) {
                    require(z.getJarEntry(entry.getKey()) != null, "Quiescence native entry absent");
                    try (java.io.InputStream in = z.getInputStream(z.getJarEntry(entry.getKey()))) {
                        require(
                            bytesSha(in).equals(
                                entry.getValue()
                                    .getAsString()),
                            "Quiescence physical native entry drift");
                    }
                }
            }
        }
        Path own = PackagedClassResourceResolver.artifactOf(PopulationQuiescenceContract.class);
        require(
            own.equals(checked(root.resolve(text(j.getAsJsonObject("artifacts"), "qa"))))
                && sha(own).equals(text(j.getAsJsonObject("files"), text(j.getAsJsonObject("artifacts"), "qa"))),
            "Quiescence QA CodeSource differs");
        verifyQaJar(own);
        launch = j;
        selected = true;
    }

    public static JsonObject actualProperties() {
        JsonObject j = new JsonObject();
        String[] names = { "Revision", "ContractSha256", "DesignSha256", "ApprovalSha256", "SourceLedgerSha256" };
        String[] keys = { "revision", "contractSha256", "designSha256", "approvalSha256", "sourceLedgerSha256" };
        for (int i = 0; i < keys.length; i++)
            j.addProperty(keys[i], System.getProperty("tlmnh.qaPopulationQuiescence" + names[i], ""));
        return j;
    }

    public static void verifyProperties(JsonObject j) {
        String[] names = { "Revision", "ContractSha256", "DesignSha256", "ApprovalSha256", "SourceLedgerSha256" };
        String[] keys = { "revision", "contractSha256", "designSha256", "approvalSha256", "sourceLedgerSha256" };
        JsonObject expected = identity();
        for (int i = 0; i < keys.length; i++) require(
            text(expected, keys[i]).equals(System.getProperty("tlmnh.qaPopulationQuiescence" + names[i], "")),
            "Quiescence actual JVM property differs: " + keys[i]);
        require(
            text(j, "mode").equals(System.getProperty("tlmnh.qaEffectEmptyWorldMode", "")),
            "Quiescence actual JVM mode differs");
    }

    public static void verifyQaJar(Path file) throws Exception {
        try (JarFile jar = new JarFile(file.toFile())) {
            JsonObject expected = parse(
                pinned().getAsJsonObject("qaManifest")
                    .toString());
            String sheddingRequest = System.getProperty("tlmnh.qaBotaniaShedding");
            if (sheddingRequest != null) {
                require("botania-native-shedding-v1".equals(sheddingRequest), "Unknown Botania manifest request");
                require(
                    "mixins.tlmnhqa.population-quiescence.json".equals(text(expected, "MixinConfigs")),
                    "Original quiescence manifest policy changed");
                expected.addProperty(
                    "MixinConfigs",
                    "mixins.tlmnhqa.population-quiescence.json,mixins.tlmnhqa.botania-shedding.json");
                java.util.jar.JarEntry shedding = jar.getJarEntry("mixins.tlmnhqa.botania-shedding.json");
                require(shedding != null, "Botania manifest supplement config absent");
                try (java.io.InputStream input = jar.getInputStream(shedding)) {
                    require(
                        "ca57d7c91aa185081796db73550c6e52648c6da28395168dd5b9cf688b00a167".equals(bytesSha(input)),
                        "Botania manifest supplement config differs");
                }
            }
            for (Map.Entry<String, JsonElement> e : expected.entrySet()) require(
                e.getValue()
                    .getAsString()
                    .equals(
                        jar.getManifest()
                            .getMainAttributes()
                            .getValue(e.getKey())),
                "QA unique mixin manifest differs: " + e.getKey());
            require(
                jar.getManifest()
                    .getMainAttributes()
                    .getValue("FMLCorePlugin") == null,
                "Unexpected QA core plugin");
            try (java.io.InputStream in = jar.getInputStream(jar.getJarEntry(CONFIG))) {
                byte[] bytes = read(in);
                JsonObject c = parse(new String(bytes, StandardCharsets.UTF_8));
                require(
                    "com.tianluoqaq.tlmnh.compat.hodgepodge.development.population.mixin".equals(text(c, "package"))
                        && "com.tianluoqaq.tlmnh.compat.hodgepodge.development.population.HodgePopulationProbeMixinPlugin"
                            .equals(text(c, "plugin"))
                        && c.getAsJsonArray("mixins")
                            .size() == 1
                        && "HodgePopulationSchedulerMixin".equals(
                            c.getAsJsonArray("mixins")
                                .get(0)
                                .getAsString()),
                    "QA unique mixin config differs");
                String provider = "com/tianluoqaq/tlmnh/compat/hodgepodge/development/population/";
                for (String entry : new String[] { "HodgePopulationProbe.class", "HodgePopulationProbe$Loaded.class",
                    "HodgePopulationProbe$Player.class", "HodgePopulationProbe$Observation.class",
                    "HodgePopulationProbeMixinPlugin.class", "mixin/HodgePopulationSchedulerMixin.class" }) {
                    require(jar.getJarEntry(provider + entry) != null, "QA provider entry absent: " + entry);
                    try (java.io.DataInputStream type = new java.io.DataInputStream(
                        jar.getInputStream(jar.getJarEntry(provider + entry)))) {
                        require(type.readInt() == 0xcafebabe, "QA provider class header differs");
                        type.readUnsignedShort();
                        require(type.readUnsignedShort() == 52, "QA provider class is not Java8");
                    }
                }
                require(
                    jar.getJarEntry(provider + "HodgePopulationSchedulerMixin.class") == null,
                    "Legacy mixin location remains packaged");
            }
        }
    }

    public static synchronized void selectionFailed(Throwable problem) {
        if (selectionFailure == null) selectionFailure = problem.toString();
        selected = false;
    }

    public static synchronized boolean selected() {
        return selected && selectionFailure == null;
    }

    public static synchronized JsonObject selection() {
        JsonObject j = new JsonObject();
        j.addProperty("requested", requested());
        j.addProperty("selected", selected());
        j.addProperty("failure", selectionFailure);
        j.addProperty("config", CONFIG);
        j.addProperty("transformedDefinitionBytesVerified", false);
        return j;
    }

    public static synchronized JsonObject certify(Class<?> type) throws Exception {
        JsonObject old = SOURCES.get(type);
        if (old != null) return old;
        require(selected() && launch != null, "Quiescence hook not selected");
        JsonObject natives = pinned().getAsJsonObject("nativeArtifacts");
        Path root = Paths.get(text(launch, "instanceRoot"));
        String entry = type.getName()
            .replace('.', '/') + ".class";
        for (Map.Entry<String, JsonElement> e : natives.entrySet()) {
            JsonObject n = e.getValue()
                .getAsJsonObject();
            if (!n.getAsJsonObject("entries")
                .has(entry)) continue;
            Path p = checked(root.resolve(e.getKey()));
            PackagedClassResourceResolver.Binding b = PackagedClassResourceResolver.resolve(type, p);
            require(
                b.physicalEntry.equals(entry) && b.sha256.equals(text(n.getAsJsonObject("entries"), entry)),
                "Quiescence public API packaged source differs");
            JsonObject r = new JsonObject();
            r.addProperty("className", type.getName());
            r.addProperty("artifact", e.getKey());
            r.addProperty("artifactSha256", text(n, "sha256"));
            r.addProperty("physicalEntry", b.physicalEntry);
            r.addProperty("classSha256", b.sha256);
            r.addProperty("transformedDefinitionBytesVerified", false);
            SOURCES.put(type, r);
            return r;
        }
        throw new IllegalStateException("Unapproved quiescence public source " + type.getName());
    }

    private static JsonElement normalized(JsonElement value) {
        if (value == null || value.isJsonNull()) return JsonNull.INSTANCE;
        if (value.isJsonObject()) {
            JsonObject r = new JsonObject();
            java.util.TreeMap<String, JsonElement> sorted = new java.util.TreeMap<>();
            for (Map.Entry<String, JsonElement> e : value.getAsJsonObject()
                .entrySet()) sorted.put(e.getKey(), e.getValue());
            for (Map.Entry<String, JsonElement> e : sorted.entrySet()) r.add(e.getKey(), normalized(e.getValue()));
            return r;
        }
        if (value.isJsonArray()) {
            JsonArray r = new JsonArray();
            for (JsonElement e : value.getAsJsonArray()) r.add(normalized(e));
            return r;
        }
        JsonPrimitive p = value.getAsJsonPrimitive();
        String n = p.getAsString();
        if (p.isNumber() && (n.contains(".") || n.contains("e") || n.contains("E")))
            return new JsonPrimitive("F64:" + String.format("%016x", Double.doubleToRawLongBits(p.getAsDouble())));
        return p;
    }

    public static String proofDigest(JsonObject proof) throws Exception {
        java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(
            new GsonBuilder().disableHtmlEscaping()
                .create()
                .toJson(normalized(proof))
                .getBytes(StandardCharsets.UTF_8));
        return bytesSha(in);
    }

    public static JsonObject receipt(JsonObject finalRow, JsonObject declaration, String finalSha) throws Exception {
        validateSavedObservation(finalRow, declaration);
        JsonObject auth = finalRow.getAsJsonObject("worldAuthentication"),
            proof = auth.getAsJsonObject("dim0QuiescenceProof");
        JsonObject r = new JsonObject();
        r.addProperty("schema", "effect-native-population-quiescence-driver-receipt-v1");
        r.addProperty("passed", true);
        r.add("quiescenceIdentity", identity());
        r.addProperty("mode", text(declaration, "mode"));
        for (String k : new String[] { "driverSha256", "sourceBaselineReceiptSha256", "sourceInventorySha256" })
            r.addProperty(k, text(declaration, k));
        for (String k : new String[] { "main", "qa" }) r.addProperty(
            k + "Sha256",
            text(declaration.getAsJsonObject("files"), text(declaration.getAsJsonObject("artifacts"), k)));
        r.addProperty("finalObservationSha256", finalSha);
        r.add("proofSeq", auth.get("dim0QuiescenceProofSeq"));
        r.addProperty("proofSha256", proofDigest(proof));
        r.addProperty("proofDigestFormat", "canonical-json-f64-bits-v1");
        r.addProperty("privateQueueOrIOEmptinessClaimed", false);
        r.addProperty("transformedDefinitionBytesVerified", false);
        return r;
    }

    private static long number(JsonObject j, String k) {
        JsonElement e = j.get(k);
        require(
            e != null && e.isJsonPrimitive()
                && e.getAsJsonPrimitive()
                    .isNumber()
                && e.getAsString()
                    .matches("-?[0-9]+"),
            "Saved integral value differs: " + k);
        return e.getAsLong();
    }

    public static void validateSavedObservation(JsonObject f, JsonObject d) {
        require(
            identity().equals(d.get("quiescenceIdentity")) && identity().equals(f.get("quiescenceIdentity"))
                && d.equals(f.get("declaration"))
                && text(d, "mode").equals(text(f, "mode"))
                && text(f, "phase").equals("CLOSED")
                && f.get("runFinished")
                    .getAsBoolean()
                && f.get("failure")
                    .isJsonNull(),
            "Saved final mode/identity differs");
        JsonObject o = f.getAsJsonObject("quiescence"), h = o.getAsJsonObject("hook");
        String mode = text(d, "mode");
        require(
            identity().equals(o.get("quiescenceIdentity")) && identity().equals(o.get("actualProperties"))
                && text(o, "actualMode").equals(mode),
            "Saved actual properties differ");
        require(
            h.get("requested")
                .getAsBoolean()
                && h.get("selected")
                    .getAsBoolean()
                && text(h, "config").equals(CONFIG)
                && h.get("failure")
                    .isJsonNull()
                && number(h, "ownerHeads") >= number(h, "ownerReturns")
                && number(h, "ownerReturns") > 0
                && number(h, "ownerToken") == 1
                && number(h, "inFlight") == 0
                && h.get("valid")
                    .getAsBoolean()
                && number(h, "hookCalls") > 0,
            "Saved natural hook publication absent");
        long checks = number(o, "sealChecks");
        require(checks >= 0 && number(o, "sealFailures") == 0, "Saved seal failure");
        if (checks == 0) require(
            (mode.equals("N") || mode.equals("P")) && o.get("lastSeal")
                .isJsonNull(),
            "Saved RUN has no sealed consumption");
        else require(
            !mode.equals("N") && o.getAsJsonObject("lastSeal")
                .get("accepted")
                .getAsBoolean(),
            "Saved seal was not quiescent");
        JsonObject auth = f.getAsJsonObject("worldAuthentication"), p = auth.getAsJsonObject("dim0QuiescenceProof");
        int seq = (int) number(auth, "dim0QuiescenceProofSeq");
        JsonArray events = f.getAsJsonArray("events");
        require(
            seq >= 0 && seq < events.size()
                && text(
                    events.get(seq)
                        .getAsJsonObject(),
                    "kind").equals("preparationQuiescenceProof")
                && text(
                    events.get(seq)
                        .getAsJsonObject(),
                    "phase").equals("PREPARING")
                && events.get(seq)
                    .getAsJsonObject()
                    .get("details")
                    .equals(p)
                && number(o, "proofSeq") == seq,
            "Saved proof event differs");
        require(
            text(p, "schema").equals("effect-native-population-quiescence-proof-v1")
                && identity().equals(p.get("quiescenceIdentity"))
                && text(p, "mode").equals(mode)
                && p.get("available")
                    .getAsBoolean()
                && p.get("accepted")
                    .getAsBoolean()
                && text(p, "reason").equals("ACCEPTED")
                && p.get("sameWorldProviderSchedulerStatsThread")
                    .getAsBoolean()
                && number(p, "ownerToken") == 1
                && number(p, "inFlight") == 0
                && number(p, "currentServerTick") == number(p, "publishedServerTick")
                && number(p, "currentServerTick") >= 0
                && number(p, "currentWorldTick") == number(p, "publishedWorldTick")
                && number(p, "currentWorldTick") >= 0
                && number(p, "generation") == number(p, "publishedGeneration")
                && number(p, "generation") > 0
                && number(p, "publishSequence") > 0,
            "Saved freshness/owner differs");
        int rawSeq = (int) number(auth, "originalDim0PopulationSnapshotSeq");
        require(
            rawSeq >= 0 && rawSeq < seq
                && number(p, "rawPopulationSnapshotSeq") == rawSeq
                && text(
                    events.get(rawSeq)
                        .getAsJsonObject(),
                    "kind").equals("preparationPopulationSnapshot")
                && events.get(rawSeq)
                    .getAsJsonObject()
                    .get("details")
                    .equals(auth.get("originalDim0PopulationSnapshot")),
            "Saved raw event linkage differs");
        JsonObject raw = auth.getAsJsonObject("originalDim0PopulationSnapshot"),
            values = p.getAsJsonObject("publicValues");
        require(
            raw.get("available")
                .getAsBoolean()
                && raw.get("schedulerPresent")
                    .getAsBoolean(),
            "Saved raw unavailable");
        for (String k : new String[] { "genQueue", "popQueue", "pipelineBacklog", "popCount" })
            require(number(raw, k) == number(values, k) && number(values, k) >= 0, "Saved raw population changed");
        for (String k : new String[] { "genQueue", "popCount", "terrainGenCount", "neighborFills", "populationDepth" })
            require(number(values, k) == 0, "Saved native activity");
        boolean primary = false;
        for (JsonElement e : auth.getAsJsonArray("worldProofs")) {
            JsonObject w = e.getAsJsonObject();
            if (number(w, "dimension") == 0) {
                require(
                    number(w, "worldIdentity") == number(p, "worldIdentity")
                        && number(w, "loadSeq") == number(p, "credentialLoadSeq"),
                    "Saved authenticated owner differs");
                primary = true;
            }
        }
        require(primary, "Saved primary absent");
        JsonObject nativeSource = pinned().getAsJsonObject("nativeArtifacts")
            .getAsJsonObject("mods/hodgepodge-2.7.221.jar");
        for (String[] pair : new String[][] { { "schedulerSource", "ChunkGenScheduler" },
            { "statsSource", "ChunkGenStats" } }) {
            JsonObject source = p.getAsJsonObject(pair[0]);
            String entry = "com/mitchej123/hodgepodge/mixins/hooks/" + pair[1] + ".class";
            require(
                text(source, "artifact").equals("mods/hodgepodge-2.7.221.jar")
                    && text(source, "artifactSha256").equals(text(nativeSource, "sha256"))
                    && text(source, "physicalEntry").equals(entry)
                    && text(source, "className").equals(
                        entry.substring(0, entry.length() - 6)
                            .replace('/', '.'))
                    && text(source, "classSha256").equals(text(nativeSource.getAsJsonObject("entries"), entry))
                    && !source.get("transformedDefinitionBytesVerified")
                        .getAsBoolean(),
                "Saved public source differs");
        }
        Set<String> active = new HashSet<>();
        for (int i = 0; i <= seq; i++) {
            JsonObject event = events.get(i)
                .getAsJsonObject();
            String kind = text(event, "kind");
            if (!kind.equals("chunkLoad") && !kind.equals("chunkUnload")) continue;
            JsonObject row = event.getAsJsonObject("details");
            if (number(row, "dimension") != 0) continue;
            require(number(row, "worldIdentity") == number(p, "worldIdentity"), "Saved topology event owner differs");
            String coordinate = number(row, "x") + ":" + number(row, "z");
            if (kind.equals("chunkLoad")) require(active.add(coordinate), "Duplicate active topology coordinate");
            else require(active.remove(coordinate), "Topology unload without loaded coordinate");
        }
        Set<String> publicRows = new HashSet<>();
        for (JsonElement e : p.getAsJsonArray("loaded")) {
            JsonObject row = e.getAsJsonObject();
            require(publicRows.add(number(row, "x") + ":" + number(row, "z")), "Saved topology duplicate");
        }
        require(active.equals(publicRows), "Saved public topology differs from original whole history");
        JsonObject readiness = f.getAsJsonObject("readinessObservation");
        long preparation = number(p, "preparationTicks");
        require(
            preparation > 0 && preparation <= 1200
                && preparation == number(f, "preparationTicks")
                && preparation == number(readiness, "lastSampleTick")
                && readiness.get("currentTickEvaluated")
                    .getAsBoolean()
                && readiness.get("flags")
                    .getAsBoolean()
                && readiness.get("idle")
                    .getAsBoolean()
                && readiness.get("signatureCompared")
                    .getAsBoolean()
                && readiness.get("signatureMatched")
                    .getAsBoolean()
                && number(readiness, "stableAfter") >= 2
                && number(readiness, "maxStableSamples") >= 2
                && number(p, "stableSamples") >= 2,
            "Saved original readiness/stable2/1200 differs");
        require(
            number(f, "observerCalls") == number(f, "fixtureObserverCalls") + number(h, "unpaidHookCalls")
                && number(f, "observerWallNanos")
                    == number(f, "fixtureObserverWallNanos") + number(h, "unpaidHookWallNanos")
                && number(h, "unpaidHookWallNanos") >= 0
                && number(h, "unpaidHookWallNanos") <= number(h, "hookWallNanos"),
            "Saved observer cost composition differs");
    }

    private static byte[] read(java.io.InputStream in) throws Exception {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int n; (n = in.read(buffer)) >= 0;) b.write(buffer, 0, n);
        return b.toByteArray();
    }

    private static String bytesSha(java.io.InputStream in) throws Exception {
        StringBuilder b = new StringBuilder();
        for (byte v : MessageDigest.getInstance("SHA-256")
            .digest(read(in))) b.append(String.format("%02x", v & 255));
        return b.toString();
    }
}
