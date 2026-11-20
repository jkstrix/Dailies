package comedymonkey.dailiescompat.jade.client;

import com.mojang.logging.LogUtils;
import comedymonkey.dailiescompat.jade.state.JadeStates;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec2;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import snownee.jade.api.fluid.JadeFluidObject;
import snownee.jade.api.theme.IThemeHelper;
import snownee.jade.api.ui.BoxStyle;
import snownee.jade.api.ui.IElement;
import snownee.jade.api.ui.MessageType;
import snownee.jade.api.ui.ProgressStyle;
import snownee.jade.api.ui.ScreenDirection;
import snownee.jade.impl.Tooltip;
import snownee.jade.impl.ui.ArmorElement;
import snownee.jade.impl.ui.BoxElement;
import snownee.jade.impl.ui.CompoundElement;
import snownee.jade.impl.ui.FluidStackElement;
import snownee.jade.impl.ui.HealthElement;
import snownee.jade.impl.ui.HorizontalLineElement;
import snownee.jade.impl.ui.ItemStackElement;
import snownee.jade.impl.ui.ProgressElement;
import snownee.jade.impl.ui.SimpleProgressStyle;
import snownee.jade.impl.ui.SlimProgressStyle;
import snownee.jade.impl.ui.SpacerElement;
import snownee.jade.impl.ui.SpecialTextElement;
import snownee.jade.impl.ui.SpriteElement;
import snownee.jade.impl.ui.TextElement;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Jade's elements to the state and back. Recording reads each element of the box Jade drew, Jade's own kinds whole (text,
 * items, progress bars and the fluid or energy in them, fluids, hearts, armour, sprites, spaces, lines across, the boxes
 * inside the box and an icon with a small one on its corner) and the size each took, so playback lays the box out
 * exactly as it was. Playback builds Jade's own elements from the state again and hands them to Jade to draw. An element
 * of a plugin's own kind, which only that plugin knows how to draw, keeps its place empty, and is named in the log once.
 */
final class Elements {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ScreenDirection[] SIDES = ScreenDirection.values();
    private static final MessageType[] MESSAGES = MessageType.values();
    private static final IElement.Align[] ALIGNS = IElement.Align.values();
    /** Kinds of element said once in the log, while recording and in playback */
    private static final Set<String> SAID = ConcurrentHashMap.newKeySet();
    /** A style's JSON, made once for each style: Jade's styles are the theme's own, the same few objects every tick */
    private static final Map<BoxStyle, String> STYLES = new IdentityHashMap<>();

    private Elements() {
    }

    // --- Recording ---

    /** The element as Jade laid it out, and everything inside it */
    static JadeStates.Element write(IElement element, int depth) {
        Vec2 move = element.getTranslation();
        Vec2 size = element.getCachedSize();
        ResourceLocation tag = element.getTag();
        Parts parts = new Parts();
        int kind;
        try {
            kind = depth > 12 ? JadeStates.OTHER : parts(element, parts, depth);
        } catch (RuntimeException unreadable) {
            // A field renamed in a later Jade: the element keeps its place, empty
            sayOnce("read " + element.getClass().getName(), "Dailies Jade compat: one of Jade's elements ({}) could not be read, so recordings leave it out",
                element.getClass().getName(), unreadable);
            parts = new Parts();
            parts.texts.add(element.getClass().getName());
            kind = JadeStates.OTHER;
        }
        return new JadeStates.Element(kind, element.getAlignment().ordinal(), move.x, move.y, size.x, size.y, tag == null ? "" : tag.toString(),
            parts.texts, parts.ints.stream().mapToInt(Integer::intValue).toArray(), floats(parts.floats), parts.children, parts.lines);
    }

    /** What each kind needs, as JadeStates describes it; the kind */
    private static int parts(IElement element, Parts parts, int depth) {
        Class<?> type = element.getClass();
        if (type != BoxElement.class && type != CompoundElement.class && type != SpecialTextElement.class && type != TextElement.class
            && type != ItemStackElement.class && type != ProgressElement.class && type != FluidStackElement.class && type != HealthElement.class
            && type != ArmorElement.class && type != SpriteElement.class && type != SpacerElement.class && type != HorizontalLineElement.class) {
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
        if (element instanceof BoxElement box) {
            parts.texts.add(style(box.getStyle()));
            for (ScreenDirection side : SIDES) {
                parts.ints.add(box.padding(side));
            }
            MessageType progressType = Reflect.get(box, "boxProgressType");
            parts.ints.add(progressType == null ? -1 : progressType.ordinal());
            parts.ints.add(box.getTooltip().sneakyDetails ? 1 : 0);
            Vec2 content = Reflect.get(box, "contentSize");
            parts.floats.add(progressType == null ? 0.0F : box.getBoxProgress());
            parts.floats.add(content.x);
            parts.floats.add(content.y);
            if (box.getIcon() != null) {
                parts.children.add(write(box.getIcon(), depth + 1));
            }
            for (Tooltip.Line line : box.getTooltip().lines) {
                List<JadeStates.Element> elements = new ArrayList<>();
                for (IElement each : line.sortedElements()) {
                    elements.add(write(each, depth + 1));
                }
                parts.lines.add(new JadeStates.Line(line.marginTop, line.marginBottom, elements));
            }
            return JadeStates.BOX;
        }
        if (element instanceof CompoundElement) {
            parts.children.add(write(Reflect.<IElement>get(element, "large"), depth + 1));
            parts.children.add(write(Reflect.<IElement>get(element, "small"), depth + 1));
            return JadeStates.COMPOUND;
        }
        if (element instanceof SpecialTextElement text) {
            parts.texts.add(Json.text(text.text));
            parts.floats.add(Reflect.<Float>get(text, "scale"));
            parts.floats.add((float) Reflect.<Integer>get(text, "zOffset"));
            return JadeStates.TEXT;
        }
        if (element instanceof TextElement text) {
            parts.texts.add(Json.text(text.text));
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
            parts.texts.add(style(progress.getStyle()));
            Component text = Reflect.get(progress, "text");
            if (text != null) {
                parts.texts.add(Json.text(text));
            }
            parts.floats.add(Reflect.<Float>get(progress, "progress"));
            ProgressStyle style = Reflect.get(progress, "style");
            parts.ints.add(Reflect.<Boolean>get(progress, "canDecrease") ? 1 : 0);
            if (style instanceof SimpleProgressStyle simple) {
                parts.ints.add(JadeStates.SIMPLE_BAR);
                parts.ints.add(simple.color);
                parts.ints.add(simple.color2);
                parts.ints.add(simple.textColor);
                parts.ints.add(simple.autoTextColor ? 1 : 0);
            } else if (style instanceof SlimProgressStyle slim) {
                parts.ints.add(JadeStates.SLIM_BAR);
                parts.ints.add(slim.color);
                parts.ints.add(slim.color);
                parts.ints.add(0);
                parts.ints.add(1);
            } else {
                sayOnce("bar " + style.getClass().getName(), "Dailies Jade compat: {} is a plugin's own progress bar, drawn in recordings as Jade's simple bar",
                    style.getClass().getName(), null);
                parts.ints.add(JadeStates.OTHER_BAR);
                parts.ints.add(-1);
                parts.ints.add(-1);
                parts.ints.add(0);
                parts.ints.add(1);
            }
            parts.ints.add(style.direction().ordinal());
            parts.ints.add(style.fitContentX() ? 1 : 0);
            parts.ints.add(style.fitContentY() ? 1 : 0);
            IElement overlay = Reflect.get(style, "overlay");
            if (overlay != null) {
                parts.children.add(write(overlay, depth + 1));
            }
            return JadeStates.PROGRESS;
        }
        if (element instanceof FluidStackElement) {
            parts.texts.add(Json.write(JadeFluidObject.CODEC, Reflect.<JadeFluidObject>get(element, "fluid")));
            return JadeStates.FLUID;
        }
        if (element instanceof HealthElement || element instanceof ArmorElement) {
            boolean health = element instanceof HealthElement;
            parts.floats.add(Reflect.<Float>get(element, health ? "health" : "armor"));
            parts.ints.add(Reflect.<Integer>get(element, "iconsPerLine"));
            parts.ints.add(Reflect.<Integer>get(element, "lineCount"));
            parts.ints.add(Reflect.<Integer>get(element, "iconCount"));
            String text = Reflect.get(element, "text");
            if (text != null) {
                parts.texts.add(text);
            }
            return health ? JadeStates.HEALTH : JadeStates.ARMOR;
        }
        if (element instanceof SpriteElement) {
            parts.texts.add(Reflect.<ResourceLocation>get(element, "sprite").toString());
            parts.ints.add(Reflect.<Integer>get(element, "width"));
            parts.ints.add(Reflect.<Integer>get(element, "height"));
            return JadeStates.SPRITE;
        }
        if (element instanceof HorizontalLineElement line) {
            parts.ints.add(line.color);
            return JadeStates.RULE;
        }
        return JadeStates.SPACER;
    }

    private static boolean isJades(IElement element) {
        return element instanceof BoxElement || element instanceof CompoundElement || element instanceof TextElement || element instanceof ItemStackElement
            || element instanceof ProgressElement || element instanceof FluidStackElement || element instanceof HealthElement
            || element instanceof ArmorElement || element instanceof SpriteElement || element instanceof SpacerElement
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

    /** Jade's own element for a recorded one, laid out at the size it took on the recorder's game */
    static IElement build(JadeStates.Element recorded, BoxStyle fallbackStyle) {
        IElement element = switch (recorded.kind()) {
            case JadeStates.TEXT -> text(recorded);
            case JadeStates.ITEM -> item(recorded);
            case JadeStates.PROGRESS -> progress(recorded);
            case JadeStates.FLUID -> {
                JadeFluidObject fluid = Json.read(JadeFluidObject.CODEC, recorded.text(0));
                yield fluid == null ? null : new FluidStackElement(fluid);
            }
            case JadeStates.HEALTH -> hearts(recorded, true);
            case JadeStates.ARMOR -> hearts(recorded, false);
            case JadeStates.SPRITE -> {
                ResourceLocation sprite = ResourceLocation.tryParse(recorded.text(0));
                yield sprite == null ? null : new SpriteElement(sprite, recorded.number(0, 0), recorded.number(1, 0));
            }
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
        if (element == null) {
            // Its place, empty: a space, or what could not be read
            element = new SpacerElement(new Vec2(recorded.width(), recorded.height()));
        }
        element.align(recorded.align() >= 0 && recorded.align() < ALIGNS.length ? ALIGNS[recorded.align()] : IElement.Align.LEFT);
        element.translate(new Vec2(recorded.moveX(), recorded.moveY()));
        element.size(new Vec2(recorded.width(), recorded.height()));
        ResourceLocation tag = recorded.tag().isEmpty() ? null : ResourceLocation.tryParse(recorded.tag());
        if (tag != null) {
            element.tag(tag);
        }
        return element;
    }

    private static IElement text(JadeStates.Element recorded) {
        Component text = Json.readText(recorded.text(0));
        if (recorded.floats().length < 2) {
            return new TextElement(text);
        }
        SpecialTextElement special = new SpecialTextElement(text);
        special.scale(recorded.fraction(0, 1.0F));
        special.zOffset((int) recorded.fraction(1, 0.0F));
        return special;
    }

    private static @Nullable IElement item(JadeStates.Element recorded) {
        ItemStack stack = Json.read(ItemStack.OPTIONAL_CODEC, recorded.text(0));
        if (stack == null || stack.isEmpty()) {
            // Jade draws nothing for no item; its own empty element is shared, so never laid out here
            return null;
        }
        stack.setCount(recorded.number(0, 1));
        return ItemStackElement.of(stack, recorded.fraction(0, 1.0F), recorded.texts().size() > 1 ? recorded.text(1) : null);
    }

    private static IElement progress(JadeStates.Element recorded) {
        BoxStyle boxStyle = Json.read(BoxStyle.CODEC, recorded.text(0));
        if (boxStyle == null) {
            boxStyle = BoxStyle.getNestedBox();
        }
        Component text = recorded.texts().size() > 1 ? Json.readText(recorded.text(1)) : null;
        ProgressStyle style;
        if (recorded.number(1, JadeStates.SIMPLE_BAR) == JadeStates.SLIM_BAR) {
            SlimProgressStyle slim = new SlimProgressStyle();
            slim.color = recorded.number(2, -1);
            style = slim;
        } else {
            SimpleProgressStyle simple = new SimpleProgressStyle();
            simple.color(recorded.number(2, -1), recorded.number(3, -1));
            simple.textColor = recorded.number(4, IThemeHelper.get().getNormalColor());
            simple.autoTextColor = recorded.number(5, 1) != 0;
            ScreenDirection direction = side(recorded.number(6, ScreenDirection.RIGHT.ordinal()));
            if (direction == ScreenDirection.UP || direction == ScreenDirection.RIGHT) {
                simple.direction(direction);
            }
            style = simple;
        }
        style.fitContentX(recorded.number(7, 1) != 0);
        style.fitContentY(recorded.number(8, 1) != 0);
        if (!recorded.children().isEmpty()) {
            style.overlay(build(recorded.children().getFirst(), boxStyle));
        }
        return new ProgressElement(recorded.fraction(0, 0.0F), text, style, boxStyle, recorded.number(0, 0) != 0);
    }

    private static IElement hearts(JadeStates.Element recorded, boolean health) {
        float amount = recorded.fraction(0, 0.0F);
        int icons = recorded.number(2, 0);
        // Made as Jade makes them, then given the recorder's rows and figures, which came from the recorder's settings
        IElement hearts = health ? new HealthElement(icons * 2.0F, amount) : new ArmorElement(amount);
        try {
            Reflect.set(hearts, "iconsPerLine", recorded.number(0, 1));
            Reflect.set(hearts, "lineCount", recorded.number(1, 1));
            Reflect.set(hearts, "iconCount", icons);
            Reflect.set(hearts, "text", recorded.texts().isEmpty() ? null : recorded.text(0));
        } catch (RuntimeException renamed) {
            sayOnce("hearts", "Dailies Jade compat: Jade's hearts could not be given the recorder's rows, so they are laid out by this game's settings", "",
                renamed);
        }
        return hearts;
    }

    private static IElement box(JadeStates.Element recorded, BoxStyle fallbackStyle) {
        BoxStyle style = Json.read(BoxStyle.CODEC, recorded.text(0));
        if (style == null) {
            style = fallbackStyle;
        }
        Tooltip tooltip = new Tooltip();
        for (JadeStates.Line line : recorded.lines()) {
            List<IElement> elements = new ArrayList<>(line.elements().size());
            for (JadeStates.Element each : line.elements()) {
                elements.add(build(each, BoxStyle.getNestedBox()));
            }
            if (elements.isEmpty()) {
                tooltip.lines.add(new Tooltip.Line());
            } else {
                tooltip.add(elements);
            }
            Tooltip.Line made = tooltip.lines.getLast();
            made.marginTop = line.marginTop();
            made.marginBottom = line.marginBottom();
        }
        tooltip.sneakyDetails = recorded.number(5, 0) != 0;
        BoxElement box = new BoxElement(tooltip, style);
        for (int i = 0; i < SIDES.length; i++) {
            box.setPadding(SIDES[i], recorded.number(i, style.padding(SIDES[i])));
        }
        int progressType = recorded.number(4, -1);
        if (progressType >= 0 && progressType < MESSAGES.length) {
            box.setBoxProgress(MESSAGES[progressType], recorded.fraction(0, 0.0F));
        }
        if (!recorded.children().isEmpty()) {
            box.setIcon(build(recorded.children().getFirst(), BoxStyle.getNestedBox()));
        }
        try {
            // Where Jade puts the hint at more details: worked out as it lays the box out, which playback never asks it to
            Reflect.set(box, "contentSize", new Vec2(recorded.fraction(1, 0.0F), recorded.fraction(2, 0.0F)));
        } catch (RuntimeException renamed) {
            sayOnce("content", "Dailies Jade compat: the size of a box's lines could not be put back, so the hint at more details may sit to one side", "", renamed);
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
