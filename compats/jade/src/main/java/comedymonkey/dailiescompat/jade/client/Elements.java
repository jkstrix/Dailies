package comedymonkey.dailiescompat.jade.client;

import com.mojang.logging.LogUtils;
import comedymonkey.dailiescompat.jade.state.JadeStates;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.layouts.LayoutSettings;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import snownee.jade.api.fluid.JadeFluidObject;
import snownee.jade.api.ui.BoxProgress;
import snownee.jade.api.ui.BoxStyle;
import snownee.jade.api.ui.Element;
import snownee.jade.api.ui.JadeUI;
import snownee.jade.api.ui.MessageType;
import snownee.jade.api.ui.Orientation;
import snownee.jade.api.ui.ProgressStyle;
import snownee.jade.api.ui.ScreenDirection;
import snownee.jade.api.view.ProgressView;
import snownee.jade.gui.JadeLinearLayout;
import snownee.jade.impl.Tooltip;
import snownee.jade.impl.ui.ArmorElement;
import snownee.jade.impl.ui.BoxElementImpl;
import snownee.jade.impl.ui.CompoundElement;
import snownee.jade.impl.ui.FluidStackElement;
import snownee.jade.impl.ui.HealthElement;
import snownee.jade.impl.ui.HorizontalLineElement;
import snownee.jade.impl.ui.ItemStackElement;
import snownee.jade.impl.ui.ProgressElement;
import snownee.jade.impl.ui.SimpleProgressStyle;
import snownee.jade.impl.ui.SpacerElement;
import snownee.jade.impl.ui.SpriteElement;
import snownee.jade.impl.ui.TextElementImpl;
import snownee.jade.overlay.DisplayHelper;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Jade's elements to the state and back. Recording reads each element of the box Jade drew, Jade's own kinds whole (text,
 * items, progress bars and what fills them, fluids, hearts, armour, sprites, spaces, lines across, the boxes inside the
 * box and an icon with a small one on its corner) and the room each took in its line, so playback lays the box out
 * exactly as it was. Jade lays its lines out itself, from each element's size, so an element is kept with the room it
 * took, its padding included: when that differs from the element's own size, or Jade had moved it inside its room,
 * playback puts it back in a space of that size at that place, as Jade's own size and offset do. Playback builds Jade's
 * own elements from the state again and hands them to Jade to lay out and draw. An element of a plugin's own kind, which
 * only that plugin knows how to draw, keeps its place empty, and is named in the log once.
 */
final class Elements {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ScreenDirection[] SIDES = ScreenDirection.values();
    private static final MessageType[] MESSAGES = MessageType.values();
    private static final JadeLinearLayout.Align[] ALIGNS = JadeLinearLayout.Align.values();
    private static final Orientation[] ORIENTATIONS = Orientation.values();
    private static final Hud.HeartType[] HEARTS = Hud.HeartType.values();
    /** Kinds of element said once in the log, while recording and in playback */
    private static final Set<String> SAID = ConcurrentHashMap.newKeySet();
    /** A style's JSON, made once for each style: Jade's styles are the theme's own, the same few objects every tick */
    private static final Map<BoxStyle, String> STYLES = new IdentityHashMap<>();

    private Elements() {
    }

    // --- Recording ---

    /** The element as Jade laid it out, and everything inside it */
    static JadeStates.Element write(LayoutElement element, int depth) {
        // The room it took: its own size and its padding, or the space Jade's size and offset put it in
        int width = element.getWidth();
        int height = element.getHeight();
        int moveX = 0;
        int moveY = 0;
        LayoutElement inner = element;
        if (element instanceof Element outer) {
            LayoutSettings.LayoutSettingsImpl padding = padding(outer);
            if (padding != null) {
                width += padding.paddingLeft + padding.paddingRight;
                height += padding.paddingTop + padding.paddingBottom;
                moveX += padding.paddingLeft;
                moveY += padding.paddingTop;
            }
        }
        if (element instanceof SpacerElement spacer && element.getClass() == SpacerElement.class) {
            LayoutElement wrapped = Reflect.get(spacer, "wrapped");
            if (wrapped != null) {
                inner = wrapped;
                moveX += Reflect.<Integer>get(spacer, "wrappedOffsetX");
                moveY += Reflect.<Integer>get(spacer, "wrappedOffsetY");
            }
        }
        Identifier tag = element instanceof Element tagged ? tagged.getTag() : null;
        JadeLinearLayout.Align align = element instanceof Element aligned ? aligned.getAlignSelf() : null;
        Parts parts = new Parts();
        int kind;
        try {
            kind = depth > 12 ? JadeStates.OTHER : parts(inner, parts, depth);
        } catch (RuntimeException unreadable) {
            // A field renamed in a later Jade: the element keeps its place, empty
            sayOnce("read " + inner.getClass().getName(), "Dailies Jade compat: one of Jade's elements ({}) could not be read, so recordings leave it out",
                inner.getClass().getName(), unreadable);
            parts = new Parts();
            parts.texts.add(inner.getClass().getName());
            kind = JadeStates.OTHER;
        }
        return new JadeStates.Element(kind, align == null ? -1 : align.ordinal(), moveX, moveY, width, height, tag == null ? "" : tag.toString(),
            parts.texts, parts.ints.stream().mapToInt(Integer::intValue).toArray(), floats(parts.floats), parts.children, parts.lines);
    }

    /** The padding an element asks its line for, or null for none */
    private static LayoutSettings.@Nullable LayoutSettingsImpl padding(Element element) {
        if (element.getSettings() == null) {
            return null;
        }
        LayoutSettings settings = element.getSettings().apply(LayoutSettings.defaults());
        return settings.getExposed();
    }

    /** What each kind needs, as JadeStates describes it; the kind */
    private static int parts(LayoutElement element, Parts parts, int depth) {
        Class<?> type = element.getClass();
        if (type != BoxElementImpl.class && type != CompoundElement.class && type != TextElementImpl.class && type != ItemStackElement.class
            && type != ProgressElement.class && type != FluidStackElement.class && type != HealthElement.class && type != ArmorElement.class
            && type != SpriteElement.class && type != SpacerElement.class && type != HorizontalLineElement.class) {
            if (isJades(element)) {
                // A plugin's element built on one of Jade's: drawn as Jade's own, which may differ a little
                sayOnce("like " + type.getName(), "Dailies Jade compat: {}, a plugin's element built on one of Jade's, is recorded as Jade's own kind", type.getName(),
                    null);
            } else {
                sayOnce("other " + type.getName(), "Dailies Jade compat: {} is a plugin's own element, which only that plugin can draw, so recordings keep its place empty",
                    type.getName(), null);
                parts.texts.add(type.getName());
                return JadeStates.OTHER;
            }
        }
        if (element instanceof BoxElementImpl box) {
            parts.texts.add(style(box.getStyle()));
            for (ScreenDirection side : SIDES) {
                parts.ints.add(box.getStyle().padding(side));
            }
            BoxProgress progress = Reflect.get(box, "pendingProgress");
            parts.ints.add(progress == null ? -1 : progress.type().ordinal());
            parts.ints.add(box.getTooltip().sneakyDetails ? 1 : 0);
            parts.ints.add(progress != null && progress.color() != null ? 1 : 0);
            parts.ints.add(progress != null && progress.color() != null ? progress.color() : 0);
            parts.floats.add(progress == null ? 0.0F : progress.progress());
            parts.floats.add(progress == null ? 0.0F : progress.alpha());
            if (box.getIcon() != null) {
                parts.children.add(write(box.getIcon(), depth + 1));
            }
            for (Tooltip.Line line : box.getTooltip().lines) {
                List<JadeStates.Element> elements = new ArrayList<>();
                for (LayoutElement each : line.elements()) {
                    elements.add(write(each, depth + 1));
                }
                parts.lines.add(new JadeStates.Line(line.marginTop, line.marginBottom, elements));
            }
            return JadeStates.BOX;
        }
        if (element instanceof CompoundElement) {
            parts.children.add(write(Reflect.<Element>get(element, "large"), depth + 1));
            parts.children.add(write(Reflect.<Element>get(element, "small"), depth + 1));
            return JadeStates.COMPOUND;
        }
        if (element instanceof TextElementImpl text) {
            parts.texts.add(Json.text(Reflect.<Component>get(text, "text")));
            parts.floats.add(Reflect.<Float>get(text, "scale"));
            parts.floats.add(Reflect.<Float>get(text, "alpha"));
            return JadeStates.TEXT;
        }
        if (element instanceof ItemStackElement item) {
            // The count apart: the game's JSON for an item takes no more than 99, and Jade adds up a chest's stacks
            ItemStack stack = item.getItem();
            parts.texts.add(Json.write(ItemStack.OPTIONAL_CODEC, stack.isEmpty() ? stack : stack.copyWithCount(1)));
            parts.ints.add(stack.getCount());
            String count = Reflect.get(item, "text");
            if (count != null) {
                parts.texts.add(count);
            }
            parts.floats.add(Reflect.<Float>get(item, "scale"));
            return JadeStates.ITEM;
        }
        if (element instanceof ProgressElement progress) {
            ProgressView view = Reflect.get(progress, "view");
            ProgressStyle style = view.style;
            parts.texts.add(style(view.boxStyle));
            parts.texts.add(style.foreground() == null ? "" : style.foreground().toString());
            parts.texts.add(view.text == null ? "" : Json.text(view.text));
            parts.ints.add(style.canDecrease() ? 1 : 0);
            if (style instanceof SimpleProgressStyle simple) {
                parts.ints.add(JadeStates.SIMPLE_BAR);
                parts.ints.add(simple.color);
                parts.ints.add(simple.autoTextColor ? 1 : 0);
            } else {
                sayOnce("bar " + style.getClass().getName(), "Dailies Jade compat: {} is a plugin's own progress bar, drawn in recordings as Jade's simple bar",
                    style.getClass().getName(), null);
                parts.ints.add(JadeStates.OTHER_BAR);
                parts.ints.add(0);
                parts.ints.add(1);
            }
            parts.ints.add(style.direction().ordinal());
            parts.ints.add(style.fitContentX() ? 1 : 0);
            parts.ints.add(style.fitContentY() ? 1 : 0);
            parts.ints.add(progress.getWidth());
            parts.ints.add(progress.getHeight());
            parts.ints.add(view.parts.size());
            for (ProgressView.Part part : view.parts) {
                parts.ints.add(part.id());
                parts.ints.add(part.messageType() == null ? -1 : part.messageType().ordinal());
                parts.ints.add(part.color());
                parts.ints.add(part.overlay() == null ? 0 : 1);
                parts.floats.add(part.progress());
                parts.floats.add(part.speed());
                parts.floats.add(part.target());
                if (part.overlay() != null) {
                    parts.children.add(write(part.overlay(), depth + 1));
                }
            }
            return JadeStates.PROGRESS;
        }
        if (element instanceof FluidStackElement fluid) {
            parts.texts.add(Json.write(JadeFluidObject.CODEC, Reflect.<JadeFluidObject>get(fluid, "fluid")));
            parts.ints.add(fluid.getWidth());
            parts.ints.add(fluid.getHeight());
            return JadeStates.FLUID;
        }
        if (element instanceof HealthElement || element instanceof ArmorElement) {
            boolean health = element instanceof HealthElement;
            parts.floats.add(Reflect.<Float>get(element, health ? "health" : "armor"));
            parts.ints.add(Reflect.<Integer>get(element, "iconsPerLine"));
            parts.ints.add(Reflect.<Integer>get(element, "lineCount"));
            parts.ints.add(Reflect.<Integer>get(element, "iconCount"));
            if (health) {
                parts.floats.add(Reflect.<Float>get(element, "maxHealth"));
                parts.floats.add(Reflect.<Float>get(element, "absorption"));
                parts.ints.add(Reflect.<Hud.HeartType>get(element, "heartType").ordinal());
            }
            String text = Reflect.get(element, "text");
            if (text != null) {
                parts.texts.add(text);
            }
            return health ? JadeStates.HEALTH : JadeStates.ARMOR;
        }
        if (element instanceof SpriteElement sprite) {
            if (Reflect.get(sprite, "renderPipeline") != RenderPipelines.GUI_TEXTURED) {
                sayOnce("sprite pipeline", "Dailies Jade compat: a sprite drawn {} is drawn in recordings as an ordinary sprite", "with its own render pipeline",
                    null);
            }
            parts.texts.add(Reflect.<Identifier>get(sprite, "sprite").toString());
            parts.ints.add(Reflect.<Integer>get(sprite, "oWidth"));
            parts.ints.add(Reflect.<Integer>get(sprite, "oHeight"));
            parts.ints.add(sprite.getWidth());
            parts.ints.add(sprite.getHeight());
            parts.ints.add(sprite.tiledOrientation == null ? -1 : sprite.tiledOrientation.ordinal());
            parts.ints.add(Reflect.<Integer>get(sprite, "color"));
            return JadeStates.SPRITE;
        }
        if (element instanceof HorizontalLineElement line) {
            parts.ints.add(line.color);
            return JadeStates.RULE;
        }
        return JadeStates.SPACER;
    }

    private static boolean isJades(LayoutElement element) {
        return element instanceof BoxElementImpl || element instanceof CompoundElement || element instanceof TextElementImpl
            || element instanceof ItemStackElement || element instanceof ProgressElement || element instanceof FluidStackElement
            || element instanceof HealthElement || element instanceof ArmorElement || element instanceof SpriteElement || element instanceof SpacerElement
            || element instanceof HorizontalLineElement;
    }

    private static synchronized String style(BoxStyle style) {
        if (STYLES.size() > 64) {
            // Themes loaded again bring new styles; the old ones are let go
            STYLES.clear();
        }
        return STYLES.computeIfAbsent(style, each -> Json.write(BoxStyle.CODEC, each));
    }

    // --- Playback ---

    /**
     * The main box, built again: itself, never put in a space of the size it took, which it only had for the moment
     * Jade's box grew or shrank to its new size (playback gives it that size as it draws it)
     */
    static @Nullable BoxElementImpl root(JadeStates.Element recorded, BoxStyle fallbackStyle) {
        if (recorded.kind() != JadeStates.BOX) {
            return null;
        }
        BoxElementImpl box = box(recorded, fallbackStyle);
        Identifier tag = recorded.tag().isEmpty() ? null : Identifier.tryParse(recorded.tag());
        if (tag != null) {
            box.tag(tag);
        }
        return box;
    }

    /** Jade's own element for a recorded one, in the room it took on the recorder's game */
    static Element build(JadeStates.Element recorded, BoxStyle fallbackStyle) {
        Element element = switch (recorded.kind()) {
            case JadeStates.TEXT -> text(recorded);
            case JadeStates.ITEM -> item(recorded);
            case JadeStates.PROGRESS -> progress(recorded);
            case JadeStates.FLUID -> {
                JadeFluidObject fluid = Json.read(JadeFluidObject.CODEC, recorded.text(0));
                if (fluid == null) {
                    yield null;
                }
                FluidStackElement made = new FluidStackElement(fluid);
                made.setFreeSpace(recorded.number(0, made.getWidth()), recorded.number(1, made.getHeight()));
                yield made;
            }
            case JadeStates.HEALTH -> hearts(recorded, true);
            case JadeStates.ARMOR -> hearts(recorded, false);
            case JadeStates.SPRITE -> sprite(recorded);
            case JadeStates.RULE -> {
                HorizontalLineElement line = new HorizontalLineElement();
                line.color = recorded.number(0, line.color);
                yield line;
            }
            case JadeStates.BOX -> box(recorded, fallbackStyle);
            case JadeStates.COMPOUND -> recorded.children().size() < 2 ? null
                : new CompoundElement(build(recorded.children().get(0), fallbackStyle), build(recorded.children().get(1), fallbackStyle));
            case JadeStates.SPACER -> null;
            default -> {
                String name = recorded.text(0).isEmpty() ? "an element of kind " + recorded.kind() : recorded.text(0);
                sayOnce("left out " + name, "Dailies Jade compat: {} in this recording is a plugin's own element, which is left out of Jade's box in playback",
                    name, null);
                yield null;
            }
        };
        int width = Math.round(recorded.width());
        int height = Math.round(recorded.height());
        int moveX = Math.round(recorded.moveX());
        int moveY = Math.round(recorded.moveY());
        Identifier tag = recorded.tag().isEmpty() ? null : Identifier.tryParse(recorded.tag());
        if (element == null) {
            // Its place, empty: a space, or what could not be read
            element = JadeUI.spacer(width, height);
        } else {
            if (tag != null) {
                element.tag(tag);
            }
            if (element.getWidth() != width || element.getHeight() != height || moveX != 0 || moveY != 0) {
                // In the room it took, where Jade had put it, as Jade's own size and offset do
                element = new SpacerElement(width, height).wrapped(element).offset(moveX, moveY);
            }
        }
        if (tag != null) {
            element.tag(tag);
        }
        int align = recorded.align();
        if (align >= 0 && align < ALIGNS.length) {
            switch (ALIGNS[align]) {
                case START -> element.alignSelfStart();
                case CENTER -> element.alignSelfCenter();
                case END -> element.alignSelfEnd();
                case STRETCH -> element.alignSelfStretch();
            }
        }
        return element;
    }

    private static Element text(JadeStates.Element recorded) {
        TextElementImpl text = new TextElementImpl(Json.readText(recorded.text(0)));
        float scale = recorded.fraction(0, 1.0F);
        if (scale != 1.0F) {
            text.scale(scale);
        }
        text.alpha(recorded.fraction(1, 1.0F));
        return text;
    }

    private static @Nullable Element item(JadeStates.Element recorded) {
        ItemStack stack = Json.read(ItemStack.OPTIONAL_CODEC, recorded.text(0));
        if (stack == null || stack.isEmpty()) {
            // Jade draws nothing for no item
            return null;
        }
        stack.setCount(recorded.number(0, 1));
        return ItemStackElement.of(stack, recorded.fraction(0, 1.0F), recorded.texts().size() > 1 ? recorded.text(1) : null);
    }

    private static Element progress(JadeStates.Element recorded) {
        BoxStyle boxStyle = Json.read(BoxStyle.CODEC, recorded.text(0));
        if (boxStyle == null) {
            boxStyle = BoxStyle.nestedBox();
        }
        Identifier foreground = recorded.text(1).isEmpty() ? null : Identifier.tryParse(recorded.text(1));
        Component text = recorded.text(2).isEmpty() ? null : Json.readText(recorded.text(2));
        SimpleProgressStyle style = new SimpleProgressStyle();
        style.color = recorded.number(2, 0);
        style.autoTextColor = recorded.number(3, 1) != 0;
        ScreenDirection direction = side(recorded.number(4, ScreenDirection.RIGHT.ordinal()));
        if (direction == ScreenDirection.UP || direction == ScreenDirection.RIGHT) {
            style.direction(direction);
        }
        style.fitContentX(recorded.number(5, 1) != 0);
        style.fitContentY(recorded.number(6, 1) != 0);
        style.foreground(foreground);
        style.canDecrease(recorded.number(0, 0) != 0);
        int count = Math.max(0, recorded.number(9, 0));
        List<ProgressView.Part> parts = new ArrayList<>(count);
        int overlays = 0;
        for (int i = 0; i < count; i++) {
            int at = 10 + i * 4;
            int messageType = recorded.number(at + 1, -1);
            Element overlay = null;
            if (recorded.number(at + 3, 0) != 0 && overlays < recorded.children().size()) {
                overlay = build(recorded.children().get(overlays++), boxStyle);
            }
            float progress = Math.clamp(recorded.fraction(i * 3, 0.0F), 0.0F, 1.0F);
            parts.add(new ProgressView.Part(recorded.number(at, 0), Float.isNaN(progress) ? 0.0F : progress, recorded.fraction(i * 3 + 1, Float.NaN),
                recorded.fraction(i * 3 + 2, Float.NaN), overlay, messageType >= 0 && messageType < MESSAGES.length ? MESSAGES[messageType] : null,
                recorded.number(at + 2, -1)));
        }
        ProgressView view = new ProgressView(parts, text, style, boxStyle);
        return new ProgressElement(view, recorded.number(7, 100), recorded.number(8, text == null ? 8 : 14));
    }

    private static Element hearts(JadeStates.Element recorded, boolean health) {
        float amount = recorded.fraction(0, 0.0F);
        Element hearts;
        if (health) {
            int heart = recorded.number(3, 0);
            hearts = new HealthElement(heart >= 0 && heart < HEARTS.length ? HEARTS[heart] : Hud.HeartType.NORMAL, recorded.fraction(1, amount), amount,
                recorded.fraction(2, 0.0F));
        } else {
            hearts = new ArmorElement(amount);
        }
        // Made as Jade makes them, then given the recorder's rows and figures, which came from the recorder's settings
        try {
            int perLine = recorded.number(0, 1);
            int lines = recorded.number(1, 1);
            String text = recorded.texts().isEmpty() ? null : recorded.text(0);
            Reflect.set(hearts, "iconsPerLine", perLine);
            Reflect.set(hearts, "lineCount", lines);
            Reflect.set(hearts, "iconCount", recorded.number(2, 0));
            Reflect.set(hearts, "text", text);
            Reflect.set(hearts, "width", text != null ? DisplayHelper.font().width(text) + 10 : 8 * perLine + 1);
            Reflect.set(hearts, "height", text != null ? 9 : 5 + 4 * lines);
        } catch (RuntimeException renamed) {
            sayOnce("hearts", "Dailies Jade compat: Jade's hearts could not be given the recorder's rows, so they are laid out by this game's settings", "",
                renamed);
        }
        return hearts;
    }

    private static @Nullable Element sprite(JadeStates.Element recorded) {
        Identifier id = Identifier.tryParse(recorded.text(0));
        if (id == null) {
            return null;
        }
        SpriteElement sprite = new SpriteElement(id, recorded.number(0, 0), recorded.number(1, 0));
        sprite.setFreeSpace(recorded.number(2, sprite.getWidth()), recorded.number(3, sprite.getHeight()));
        int tiled = recorded.number(4, -1);
        if (tiled >= 0 && tiled < ORIENTATIONS.length) {
            sprite.tiledOrientation = ORIENTATIONS[tiled];
        }
        sprite.setColor(recorded.number(5, -1));
        return sprite;
    }

    private static BoxElementImpl box(JadeStates.Element recorded, BoxStyle fallbackStyle) {
        BoxStyle style = Json.read(BoxStyle.CODEC, recorded.text(0));
        if (style == null) {
            style = fallbackStyle;
        }
        Tooltip tooltip = new Tooltip();
        for (JadeStates.Line line : recorded.lines()) {
            Tooltip.Line made = new Tooltip.Line();
            for (JadeStates.Element each : line.elements()) {
                made.elements().add(build(each, BoxStyle.nestedBox()));
            }
            made.marginTop = line.marginTop();
            made.marginBottom = line.marginBottom();
            tooltip.lines.add(made);
        }
        tooltip.sneakyDetails = recorded.number(5, 0) != 0;
        if (!recorded.children().isEmpty()) {
            // Before the box is made: it lays its lines out beside the icon as it is made
            tooltip.setIcon(build(recorded.children().getFirst(), BoxStyle.nestedBox()));
        }
        BoxElementImpl box = new BoxElementImpl(tooltip, style);
        int progressType = recorded.number(4, -1);
        if (progressType >= 0 && progressType < MESSAGES.length) {
            // The line along its foot as Jade drew it then (the block being broken, on the main box): Jade works it out
            // just before it draws the box over play, which playback never asks it to
            try {
                Reflect.set(box, "pendingProgress", new BoxProgress(recorded.fraction(0, 0.0F), MESSAGES[progressType],
                    recorded.number(6, 0) != 0 ? recorded.number(7, 0) : null, recorded.fraction(1, 1.0F)));
            } catch (RuntimeException renamed) {
                sayOnce("box progress", "Dailies Jade compat: the line along a box's foot could not be put back, so playback leaves it out", "", renamed);
            }
        }
        return box;
    }

    private static ScreenDirection side(int ordinal) {
        return ordinal >= 0 && ordinal < SIDES.length ? SIDES[ordinal] : ScreenDirection.RIGHT;
    }

    // --- Helpers ---

    private static void sayOnce(String key, String message, String what, @Nullable Throwable cause) {
        if (SAID.add(key)) {
            if (cause == null) {
                LOGGER.info(message, what);
            } else {
                LOGGER.warn(message, what, cause);
            }
        }
    }

    private static float[] floats(List<Float> floats) {
        float[] out = new float[floats.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = floats.get(i);
        }
        return out;
    }

    /** What one element's kind needs, gathered as it is read */
    private static final class Parts {
        final List<String> texts = new ArrayList<>();
        final List<Integer> ints = new ArrayList<>();
        final List<Float> floats = new ArrayList<>();
        final List<JadeStates.Element> children = new ArrayList<>();
        final List<JadeStates.Line> lines = new ArrayList<>();
    }

}
