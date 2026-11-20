package dev.stowling.client.automation;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

final class MinecraftBackpackIcon {
    private static final int SIZE = 32;

    private MinecraftBackpackIcon() {
    }

    static String read(ResourceManager resources, String modelName) {
        Identifier id = identifier(modelName);
        if (id == null) return "";
        try {
            JsonObject item = json(resources, id.withPrefix("items/").withSuffix(".json"));
            String model = restingModel(item.get("model"));
            BufferedImage icon = modelIcon(resources, identifier(model));
            if (icon == null) icon = texture(resources, id.withPrefix("item/"));
            if (icon == null) icon = texture(resources, id.withPrefix("block/"));
            if (icon == null) return "";
            var bytes = new ByteArrayOutputStream();
            ImageIO.write(icon, "png", bytes);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (IOException | RuntimeException exception) {
            return "";
        }
    }

    private static String restingModel(JsonElement value) {
        if (value == null || !value.isJsonObject()) return "";
        JsonObject node = value.getAsJsonObject();
        String direct = string(node.get("model"));
        if (!direct.isEmpty()) return direct;
        String base = string(node.get("base"));
        if (!base.isEmpty()) return base;
        for (String branch : new String[]{"on_false", "fallback", "on_true"}) {
            String model = restingModel(node.get(branch));
            if (!model.isEmpty()) return model;
        }
        if (node.has("models") && node.get("models").isJsonArray()) {
            for (JsonElement child : node.getAsJsonArray("models")) {
                String model = restingModel(child);
                if (!model.isEmpty()) return model;
            }
        }
        return "";
    }

    private static BufferedImage modelIcon(ResourceManager resources, Identifier model)
            throws IOException {
        Map<String, String> textures = new LinkedHashMap<>();
        Set<Identifier> visited = new HashSet<>();
        while (model != null && visited.add(model)) {
            JsonObject definition = json(resources, model.withPrefix("models/").withSuffix(".json"));
            if (definition.has("textures") && definition.get("textures").isJsonObject()) {
                definition.getAsJsonObject("textures").entrySet()
                        .forEach(entry -> textures.putIfAbsent(entry.getKey(),
                                string(entry.getValue())));
            }
            model = identifier(string(definition.get("parent")));
        }
        BufferedImage layer = firstTexture(resources, textures, "layer0");
        if (layer != null) return layer;
        BufferedImage top = firstTexture(resources, textures, "all", "up", "top", "end");
        BufferedImage side = firstTexture(resources, textures, "all", "north", "side");
        if (top != null && side != null) {
            BufferedImage right = firstTexture(resources, textures, "east", "west");
            return cube(top, side, right == null ? side : right);
        }
        return firstTexture(resources, textures, "particle", "side", "texture", "front");
    }

    private static BufferedImage firstTexture(ResourceManager resources, Map<String, String> map,
            String... names) throws IOException {
        for (String name : names) {
            String value = map.getOrDefault(name, "");
            for (int step = 0; value.startsWith("#") && step < map.size(); step++) {
                value = map.getOrDefault(value.substring(1), "");
            }
            if (value.startsWith("#")) continue;
            BufferedImage image = texture(resources, identifier(value));
            if (image != null) return image;
        }
        return null;
    }

    private static BufferedImage texture(ResourceManager resources, Identifier id)
            throws IOException {
        if (id == null) return null;
        Identifier path = id.withPrefix("textures/").withSuffix(".png");
        var resource = resources.getResource(path);
        if (resource.isEmpty()) return null;
        try (var input = resource.orElseThrow().open()) {
            BufferedImage image = ImageIO.read(input);
            if (image != null && image.getHeight() > image.getWidth()
                    && resources.getResource(path.withSuffix(".mcmeta")).isPresent()) {
                image = image.getSubimage(0, 0, image.getWidth(), image.getWidth());
            }
            if (image == null) return null;
            BufferedImage thumbnail = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = thumbnail.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                double scale = (double) SIZE / Math.max(image.getWidth(), image.getHeight());
                int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
                int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
                graphics.drawImage(image, (SIZE - width) / 2, (SIZE - height) / 2,
                        width, height, null);
                return thumbnail;
            } finally {
                graphics.dispose();
            }
        }
    }

    private static BufferedImage cube(BufferedImage top, BufferedImage left, BufferedImage right) {
        BufferedImage icon = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = icon.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            graphics.drawImage(left, new AffineTransform(
                    13.0 / SIZE, 7.0 / SIZE, 0, 14.0 / SIZE, 3, 9), null);
            graphics.drawImage(right, new AffineTransform(
                    13.0 / SIZE, -7.0 / SIZE, 0, 14.0 / SIZE, 16, 16), null);
            graphics.drawImage(top, new AffineTransform(
                    13.0 / SIZE, 7.0 / SIZE, -13.0 / SIZE, 7.0 / SIZE, 16, 2), null);
            graphics.setColor(new java.awt.Color(0, 0, 0, 70));
            graphics.fillPolygon(new int[]{16, 29, 29, 16}, new int[]{16, 9, 23, 30}, 4);
            return icon;
        } finally {
            graphics.dispose();
        }
    }

    private static JsonObject json(ResourceManager resources, Identifier path) throws IOException {
        var resource = resources.getResource(path);
        if (resource.isEmpty()) return new JsonObject();
        try (var reader = resource.orElseThrow().openAsReader()) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static String string(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : "";
    }

    private static Identifier identifier(String value) {
        if (value.isEmpty()) return null;
        Identifier id = Identifier.tryParse(value);
        if (id == null || id.getPath().isEmpty()) return null;
        for (String part : id.getPath().split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) return null;
        }
        return id;
    }
}
