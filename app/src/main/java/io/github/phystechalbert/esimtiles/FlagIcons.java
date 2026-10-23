package io.github.phystechalbert.esimtiles;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;
import android.telephony.SubscriptionInfo;

import java.util.Locale;

/**
 * Flag icons for the tiles. The shade paints every tile icon in one colour and keeps only alpha:
 * white on a highlighted tile, dark on a dimmed one. So each flag is described by the lightness
 * of its colours (0 dark, 1 white) and turned into alpha for the tint the tile will get:
 * light areas are opaque under a white tint and transparent under a dark one.
 */
final class FlagIcons {
    private static final int SIZE = 96;
    private static final RectF FLAG = new RectF(6, 20, 90, 76); // 3:2
    /** Alpha range; the floor keeps the flag's outline visible even where it should vanish. */
    private static final int MIN_ALPHA = 30;
    private static final int MAX_ALPHA = 255;

    // Lightness of the flag colours as they should read on the tile.
    private static final float WHITE = 1f;
    private static final float GOLD = 1f;
    // Darker than the real sky blue: on a dimmed tile the sun is a cut-out and needs a solid field.
    private static final float SKY = 0.12f;
    private static final float RED = 0.45f;
    private static final float BLUE = 0.15f;

    private FlagIcons() {}

    /**
     * Icon for a SIM: a drawn flag for the countries that have one here, otherwise the country
     * code (or the start of the SIM's name) in letters. whiteTint is true for a highlighted tile,
     * whose icon the shade paints white.
     */
    static Icon forSim(SubscriptionInfo info, boolean whiteTint) {
        String iso = info.getCountryIso() != null ? info.getCountryIso().toLowerCase(Locale.ROOT) : "";
        Bitmap bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        if (!iso.equals("kz") && !iso.equals("ge") && !iso.equals("ru")) {
            String text = iso.length() == 2 ? iso.toUpperCase(Locale.ROOT) : initials(Esim.label(info));
            letters(canvas, text);
            return Icon.createWithBitmap(bitmap);
        }
        Path outline = new Path();
        outline.addRoundRect(FLAG, 7, 7, Path.Direction.CW);
        canvas.clipPath(outline);
        Shade shade = new Shade(whiteTint);
        switch (iso) {
            case "kz": kazakhstan(canvas, shade); break;
            case "ge": georgia(canvas, shade); break;
            default: russia(canvas, shade); break;
        }
        return Icon.createWithBitmap(bitmap);
    }

    /** First two letters or digits of a name, skipping emoji and punctuation. */
    private static String initials(String name) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length() && sb.length() < 2; ) {
            int cp = name.codePointAt(i);
            if (Character.isLetterOrDigit(cp)) sb.appendCodePoint(Character.toUpperCase(cp));
            i += Character.charCount(cp);
        }
        return sb.length() > 0 ? sb.toString() : "?";
    }

    private static void letters(Canvas canvas, String text) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0xFFFFFFFF);
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(52);
        float y = SIZE / 2f - (paint.descent() + paint.ascent()) / 2;
        canvas.drawText(text, SIZE / 2f, y, paint);
    }

    /** A paint whose alpha renders a given lightness under the tile's tint. */
    private static final class Shade {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final boolean whiteTint;

        Shade(boolean whiteTint) {
            this.whiteTint = whiteTint;
            paint.setColor(0xFFFFFFFF);
            // Replace, don't blend: an emblem lighter than its field must lower the field's alpha.
            paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC));
        }

        Paint of(float lightness) {
            float opacity = whiteTint ? lightness : 1f - lightness;
            paint.setAlpha(Math.round(MIN_ALPHA + (MAX_ALPHA - MIN_ALPHA) * opacity));
            return paint;
        }
    }

    /** White, blue and red bands. */
    private static void russia(Canvas canvas, Shade shade) {
        float h = FLAG.height() / 3;
        float[] bands = {WHITE, BLUE, RED};
        for (int i = 0; i < 3; i++) {
            canvas.drawRect(FLAG.left, FLAG.top + i * h, FLAG.right, FLAG.top + (i + 1) * h, shade.of(bands[i]));
        }
    }

    /** White field, big red cross, a small red cross in each quarter. */
    private static void georgia(Canvas canvas, Shade shade) {
        canvas.drawRect(FLAG, shade.of(WHITE));
        Paint red = shade.of(RED);
        float cx = FLAG.centerX();
        float cy = FLAG.centerY();
        cross(canvas, red, cx, cy, FLAG.width(), FLAG.height(), 10);
        float qx = FLAG.width() / 4;
        float qy = FLAG.height() / 4;
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sy = -1; sy <= 1; sy += 2) {
                cross(canvas, red, cx + sx * qx, cy + sy * qy, 13, 13, 4);
            }
        }
    }

    private static void cross(Canvas canvas, Paint paint, float cx, float cy, float w, float h, float t) {
        // Drawn as one path so the overlap in the middle isn't painted twice.
        Path path = new Path();
        path.addRect(cx - w / 2, cy - t / 2, cx + w / 2, cy + t / 2, Path.Direction.CW);
        path.addRect(cx - t / 2, cy - h / 2, cx + t / 2, cy + h / 2, Path.Direction.CW);
        path.setFillType(Path.FillType.WINDING);
        canvas.drawPath(path, paint);
    }

    /** Sky-blue field, golden sun over a soaring eagle, ornament band along the hoist. */
    private static void kazakhstan(Canvas canvas, Shade shade) {
        canvas.drawRect(FLAG, shade.of(SKY));
        Paint gold = shade.of(GOLD);
        float cx = FLAG.centerX() + 3;
        float cy = FLAG.top + 21;

        canvas.drawCircle(cx, cy, 8, gold);
        gold.setStrokeWidth(2.2f);
        gold.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < 16; i++) {
            double a = Math.PI * 2 * i / 16;
            float cos = (float) Math.cos(a);
            float sin = (float) Math.sin(a);
            canvas.drawLine(cx + cos * 11, cy + sin * 11, cx + cos * 15, cy + sin * 15, gold);
        }

        Path eagle = new Path();
        float y = cy + 21;
        eagle.moveTo(cx - 26, y);
        eagle.quadTo(cx - 13, y - 9, cx, y);
        eagle.quadTo(cx + 13, y - 9, cx + 26, y);
        eagle.lineTo(cx + 26, y + 4);
        eagle.quadTo(cx + 13, y - 3, cx, y + 6);
        eagle.quadTo(cx - 13, y - 3, cx - 26, y + 4);
        eagle.close();
        canvas.drawPath(eagle, gold);

        float ox = FLAG.left + 8;
        for (float oy = FLAG.top + 7; oy < FLAG.bottom - 4; oy += 9) {
            Path diamond = new Path();
            diamond.moveTo(ox, oy - 3.5f);
            diamond.lineTo(ox + 3.5f, oy);
            diamond.lineTo(ox, oy + 3.5f);
            diamond.lineTo(ox - 3.5f, oy);
            diamond.close();
            canvas.drawPath(diamond, gold);
        }
    }
}
