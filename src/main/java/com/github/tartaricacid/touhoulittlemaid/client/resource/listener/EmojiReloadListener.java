/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: client/resource/listener/EmojiReloadListener.java, MIT
 * Adaptation: legacy client rendering for builtin ordinary bubbles
 */
package com.github.tartaricacid.touhoulittlemaid.client.resource.listener;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.regex.Pattern;

import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.IResourceManagerReloadListener;
import net.minecraft.util.ResourceLocation;

import com.google.gson.JsonParser;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class EmojiReloadListener implements IResourceManagerReloadListener {

    private static final List<EmojiResource> EMOJI_RESOURCES = new ArrayList<>();
    private static final Random RANDOM = new Random();
    private static final Pattern SIZE = Pattern.compile("^.*?-(\\d+)x(\\d+)\\.png$");

    @Override
    public void onResourceManagerReload(IResourceManager manager) {
        EMOJI_RESOURCES.clear();
        var input = EmojiReloadListener.class.getResourceAsStream("/assets/tlmnh/gui-assets-provenance.json");
        if (input == null) throw new IllegalStateException("Missing builtin emoji manifest");
        try (var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            var root = new JsonParser().parse(reader)
                .getAsJsonObject();
            for (var item : root.getAsJsonArray("files")) {
                String path = item.getAsJsonObject()
                    .get("path")
                    .getAsString();
                String prefix = "assets/touhou_little_maid/";
                if (!path.startsWith(prefix + "textures/chat_bubble/maid_emoji/") || !path.endsWith(".png")) continue;
                var location = new ResourceLocation("touhou_little_maid", path.substring(prefix.length()));
                try (var resource = manager.getResource(location)
                    .getInputStream()) {
                    var matcher = SIZE.matcher(path);
                    int width = 24, height = 24;
                    if (matcher.matches()) {
                        width = Math.max(8, Math.min(256, Integer.parseInt(matcher.group(1))));
                        height = Math.max(8, Math.min(256, Integer.parseInt(matcher.group(2))));
                    }
                    EMOJI_RESOURCES.add(new EmojiResource(location, width, height));
                }
            }
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Cannot load builtin emoji", failure);
        }
    }

    public static Optional<EmojiResource> getRandomEmojis() {
        return EMOJI_RESOURCES.isEmpty() ? Optional.empty()
            : Optional.of(EMOJI_RESOURCES.get(RANDOM.nextInt(EMOJI_RESOURCES.size())));
    }

    public static final class EmojiResource {

        public final ResourceLocation location;
        public final int width, height;

        private EmojiResource(ResourceLocation location, int width, int height) {
            this.location = location;
            this.width = width;
            this.height = height;
        }
    }
}
