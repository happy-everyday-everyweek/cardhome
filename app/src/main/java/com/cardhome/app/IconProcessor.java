package com.cardhome.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.util.LruCache;

/**
 * 卡片渲染与图标处理。
 *
 * 卡片采用「逐层向下模糊」的动态渲染：把六层按顺序画到同一张离屏位图上，每一层所模糊的对象
 * 都是它下面已经渲染出来的全部内容，而不是单独模糊某张图标位图。
 *
 * 渲染顺序（自下而上）：
 * 1 底色层：主题色（动态取色）或图标主色（图标取色），约 40% 不透明，纯色不渐变；
 * 2 深色模式层：开启深色模式时叠一层 20% 的偏黑灰；
 * 3 强模糊层：把以上全部内容整体做强模糊后替换回去。
 *   卡片背后的真实壁纸不在这里处理，由 AppCardView 的背景模糊层交给系统实时模糊；
 * 4 图标层：裁到图标内容边界后按卡片高度铺满（上下不留缝），42% 起渐隐、66% 处完全透明；
 * 5 渐变模糊层：把含图标的内容再模糊一次，按 37%→65% 由淡到浓叠加，
 *   用模糊结果替换掉图标层与非图标区域之间的硬边界；
 * 6 文字层：由 AppCardView 的 TextView 叠在最上面，保持清晰。
 */
final class IconProcessor {

    private static final int BUCKETS = 32;

    private static final LruCache<String, Bitmap> ICON_CACHE =
            new LruCache<String, Bitmap>(4 * 1024 * 1024) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount();
                }
            };

    private static final LruCache<String, Integer> COLOR_CACHE =
            new LruCache<String, Integer>(256);

    private static final LruCache<String, Bitmap> CARD_CACHE =
            new LruCache<String, Bitmap>(12 * 1024 * 1024) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount();
                }
            };

    private IconProcessor() {
    }

    /**
     * 合成一整张卡片位图（不含文字）。
     *
     * @param recolor 是否对图标做灰度重映射后重新着色；false 时直接用原始图标
     */
    static Bitmap composeCard(String key, Drawable icon, int tint, boolean dark, boolean recolor,
                              int cardW, int cardH) {
        String cacheKey = "card|" + key + "|" + tint + "|" + dark + "|" + recolor + "|"
                + cardW + "x" + cardH;
        Bitmap cached = CARD_CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        Bitmap stack = null;
        try {
            stack = Bitmap.createBitmap(cardW, cardH, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(stack);

            // 第一层：底色（40% 不透明，纯色不渐变）
            Paint base = new Paint(Paint.ANTI_ALIAS_FLAG);
            base.setColor(withAlpha(tint, 0x66));
            canvas.drawRect(0f, 0f, cardW, cardH, base);

            // 第二层：深色模式层（偏黑灰，20%）
            if (dark) {
                Paint shade = new Paint();
                shade.setColor(0x33141414);
                canvas.drawRect(0f, 0f, cardW, cardH, shade);
            }

            // 第三层：强模糊层——把下面的全部内容（底色 + 背景 + 深色层）整体强模糊后替换回去
            Bitmap strong = blur(stack, 9);
            replaceWithMasked(stack, strong, 0f, 1f, 1f, 1f);
            strong.recycle();

            // 第四层：图标层——先把图标裁到自身内容边界（自适应图标四周有大片透明留白），
            // 再按卡片高度铺满，上下不留缝隙
            int renderSize = Math.max(96, Math.round(cardH * 1.5f));
            Bitmap iconBase = recolor
                    ? processedIcon(key, icon, renderSize, tint, dark)
                    : renderIcon(icon, renderSize);
            iconBase = trimToContent(iconBase);
            if (iconBase != null) {
                Bitmap iconLayer = Bitmap.createBitmap(cardW, cardH, Bitmap.Config.ARGB_8888);
                Canvas iconCanvas = new Canvas(iconLayer);
                Paint iconPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
                float drawH = cardH;
                float drawW = Math.max(1f, iconBase.getWidth() * drawH / iconBase.getHeight());
                float left = Math.max(0f, (drawH - drawW) / 2f);
                iconCanvas.drawBitmap(iconBase, null,
                        new RectF(left, 0f, left + drawW, drawH), iconPaint);
                applyMask(iconLayer, 0.42f, 1f, 0.66f, 0f);
                canvas.drawBitmap(iconLayer, 0f, 0f, null);
                iconLayer.recycle();
            }

            // 第五层：渐变模糊层——把含图标的内容再模糊一次，按 37%→65% 由淡到浓替换回来，
            // 消掉图标层与非图标区域之间那条硬边界
            Bitmap soft = blur(stack, 6);
            applyMask(soft, 0.37f, 0f, 0.65f, 1f);
            replaceWithMasked(stack, soft, 0.37f, 0f, 0.65f, 1f);
            soft.recycle();
        } catch (Throwable ignored) {
            stack = null;
        }
        if (stack != null) {
            CARD_CACHE.put(cacheKey, stack);
        }
        return stack;
    }

    /** 图标主色（图标取色模式用） */
    static int dominantColor(String key, Drawable icon) {
        Integer cached = COLOR_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        int color = 0xFFC9CFFF;
        try {
            int size = 48;
            Bitmap bitmap = renderIcon(icon, size);
            if (bitmap != null) {
                int[] pixels = new int[size * size];
                bitmap.getPixels(pixels, 0, size, 0, 0, size, size);
                int[] counts = new int[4096];
                int bestIndex = -1;
                int bestCount = 0;
                for (int pixel : pixels) {
                    int alpha = (pixel >>> 24) & 0xFF;
                    if (alpha < 48) {
                        continue;
                    }
                    int r = (pixel >> 16) & 0xFF;
                    int g = (pixel >> 8) & 0xFF;
                    int b = pixel & 0xFF;
                    int max = Math.max(r, Math.max(g, b));
                    int min = Math.min(r, Math.min(g, b));
                    float saturation = max <= 0 ? 0f : (max - min) / (float) max;
                    if (saturation < 0.18f || max < 40 || min > 235) {
                        continue;
                    }
                    int index = ((r >> 4) << 8) | ((g >> 4) << 4) | (b >> 4);
                    counts[index]++;
                    if (counts[index] > bestCount) {
                        bestCount = counts[index];
                        bestIndex = index;
                    }
                }
                if (bestIndex >= 0) {
                    int r4 = (bestIndex >> 8) & 0xF;
                    int g4 = (bestIndex >> 4) & 0xF;
                    int b4 = bestIndex & 0xF;
                    color = 0xFF000000 | ((r4 * 17) << 16) | ((g4 * 17) << 8) | (b4 * 17);
                }
                bitmap.recycle();
            }
        } catch (Throwable ignored) {
        }
        COLOR_CACHE.put(key, color);
        return color;
    }

    // ---------------------------------------------------------------- 内部实现

    /** 灰度化 -> 按面积排名重映射明暗（最大变最暗、最小变最亮）-> 重新着色 */
    private static Bitmap processedIcon(String key, Drawable icon, int size, int tint, boolean dark) {
        String cacheKey = "icon|" + key + "|" + size + "|" + tint + "|" + dark;
        Bitmap cached = ICON_CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        Bitmap result = null;
        try {
            Bitmap source = renderIcon(icon, size);
            if (source != null) {
                int n = size * size;
                int[] pixels = new int[n];
                source.getPixels(pixels, 0, size, 0, 0, size, size);

                int[] counts = new int[BUCKETS];
                int[] gray = new int[n];
                int opaque = 0;
                int bright = 0;
                for (int i = 0; i < n; i++) {
                    int pixel = pixels[i];
                    int alpha = (pixel >>> 24) & 0xFF;
                    int r = (pixel >> 16) & 0xFF;
                    int g = (pixel >> 8) & 0xFF;
                    int b = pixel & 0xFF;
                    int lum = (int) (0.299f * r + 0.587f * g + 0.114f * b);
                    gray[i] = lum;
                    if (alpha >= 32) {
                        counts[Math.min(BUCKETS - 1, lum * BUCKETS / 256)]++;
                        opaque++;
                        if (lum >= 217) {
                            bright++;
                        }
                    }
                }
                // 基本全是白色线条的图标不做白色保留，否则整幅图会变成一片白
                boolean keepWhite = opaque > 0 && bright * 100 / opaque < 85;

                int[] order = new int[BUCKETS];
                for (int i = 0; i < BUCKETS; i++) {
                    order[i] = i;
                }
                for (int i = 0; i < BUCKETS - 1; i++) {
                    for (int j = i + 1; j < BUCKETS; j++) {
                        if (counts[order[j]] > counts[order[i]]) {
                            int tmp = order[i];
                            order[i] = order[j];
                            order[j] = tmp;
                        }
                    }
                }
                int used = 0;
                for (int bucket = 0; bucket < BUCKETS; bucket++) {
                    if (counts[bucket] > 0) {
                        used++;
                    }
                }
                float[] rank = new float[BUCKETS];
                for (int i = 0; i < BUCKETS; i++) {
                    rank[order[i]] = used <= 1 ? 0.5f : (float) i / (float) (used - 1);
                }

                // 色调：暗部 = 主题色（或图标主色）混黑，亮部 = 纯白，保证白色图案不被染色、轮廓清晰
                int darkTone = mix(tint, 0xFF000000, dark ? 0.25f : 0.50f);
                int[] lut = new int[256];
                for (int level = 0; level < 256; level++) {
                    lut[level] = mix(darkTone, 0xFFFFFFFF, level / 255f);
                }

                int[] out = new int[n];
                for (int i = 0; i < n; i++) {
                    int pixel = pixels[i];
                    int alpha = (pixel >>> 24) & 0xFF;
                    if (alpha == 0) {
                        out[i] = 0;
                        continue;
                    }
                    float t;
                    if (keepWhite && gray[i] >= 217) {
                        t = 1f;
                    } else {
                        float r = rank[Math.min(BUCKETS - 1, gray[i] * BUCKETS / 256)];
                        t = dark ? (0.24f + 0.70f * r) : (0.10f + 0.66f * r);
                    }
                    int level = Math.max(0, Math.min(255, Math.round(t * 255f)));
                    out[i] = (alpha << 24) | (lut[level] & 0x00FFFFFF);
                }
                result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                result.setPixels(out, 0, size, 0, 0, size, size);
                source.recycle();
            }
        } catch (Throwable ignored) {
        }
        if (result != null) {
            ICON_CACHE.put(cacheKey, result);
        }
        return result;
    }

    /**
     * 把位图裁到实际内容边界。自适应图标在 108dp 画布里只有中间一小块是不透明的，
     * 不裁掉这圈透明留白，铺满卡片时看起来就是「上下有缝隙」。
     * 内容已经顶满时原样返回，不做多余拷贝。
     */
    private static Bitmap trimToContent(Bitmap source) {
        if (source == null) {
            return null;
        }
        try {
            int w = source.getWidth();
            int h = source.getHeight();
            if (w <= 0 || h <= 0) {
                return source;
            }
            int[] pixels = new int[w * h];
            source.getPixels(pixels, 0, w, 0, 0, w, h);
            int minX = w;
            int minY = h;
            int maxX = -1;
            int maxY = -1;
            for (int y = 0; y < h; y++) {
                int row = y * w;
                for (int x = 0; x < w; x++) {
                    if (((pixels[row + x] >>> 24) & 0xFF) >= 16) {
                        if (x < minX) {
                            minX = x;
                        }
                        if (x > maxX) {
                            maxX = x;
                        }
                        if (y < minY) {
                            minY = y;
                        }
                        if (y > maxY) {
                            maxY = y;
                        }
                    }
                }
            }
            if (maxX < minX || maxY < minY) {
                return source;
            }
            int cw = maxX - minX + 1;
            int ch = maxY - minY + 1;
            if (cw >= w && ch >= h) {
                return source;
            }
            if (cw < 4 || ch < 4) {
                return source;
            }
            Bitmap cropped = Bitmap.createBitmap(source, minX, minY, cw, ch);
            return cropped != null ? cropped : source;
        } catch (Throwable ignored) {
            return source;
        }
    }

    /** 等比绘制图标到方形位图，保持长宽比、居中 */
    private static Bitmap renderIcon(Drawable drawable, int size) {
        if (drawable == null || size <= 0) {
            return null;
        }
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        int iw = drawable.getIntrinsicWidth();
        int ih = drawable.getIntrinsicHeight();
        if (iw > 0 && ih > 0 && iw != ih) {
            float scale = Math.min((float) size / iw, (float) size / ih);
            int w = Math.max(1, Math.round(iw * scale));
            int h = Math.max(1, Math.round(ih * scale));
            int left = (size - w) / 2;
            int top = (size - h) / 2;
            drawable.setBounds(left, top, left + w, top + h);
        } else {
            drawable.setBounds(0, 0, size, size);
        }
        drawable.draw(canvas);
        return bitmap;
    }

    /** 按水平渐变遮罩给位图叠透明度：0~from 保持 alphaFrom，from~to 线性变到 alphaTo，之后保持 */
    private static void applyMask(Bitmap target, float from, float alphaFrom, float to, float alphaTo) {
        Paint mask = new Paint();
        mask.setShader(maskShader(target.getWidth(), from, alphaFrom, to, alphaTo));
        mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        new Canvas(target).drawRect(0f, 0f, target.getWidth(), target.getHeight(), mask);
    }

    /**
     * 用 masked 按遮罩替换 stack 上的对应内容（而不是叠加）。
     * 先按同一遮罩把下层内容擦掉，再画上模糊结果，这样卡片的整体不透明度不会被逐层叠加抬高。
     */
    private static void replaceWithMasked(Bitmap stack, Bitmap masked,
                                          float from, float alphaFrom, float to, float alphaTo) {
        Canvas canvas = new Canvas(stack);
        Paint clear = new Paint();
        clear.setShader(maskShader(stack.getWidth(), from, alphaFrom, to, alphaTo));
        clear.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        canvas.drawRect(0f, 0f, stack.getWidth(), stack.getHeight(), clear);
        canvas.drawBitmap(masked, 0f, 0f, null);
    }

    private static LinearGradient maskShader(int width, float from, float alphaFrom,
                                             float to, float alphaTo) {
        float p1 = Math.max(0f, Math.min(1f, from));
        float p2 = Math.max(p1, Math.min(1f, to));
        int a1 = Math.round(255f * Math.max(0f, Math.min(1f, alphaFrom)));
        int a2 = Math.round(255f * Math.max(0f, Math.min(1f, alphaTo)));
        return new LinearGradient(0f, 0f, Math.max(1, width), 0f,
                new int[]{(a1 << 24) | 0x00FFFFFF, (a1 << 24) | 0x00FFFFFF,
                        (a2 << 24) | 0x00FFFFFF, (a2 << 24) | 0x00FFFFFF},
                new float[]{0f, p1, p2, 1f}, Shader.TileMode.CLAMP);
    }

    /** 先把位图缩小再放大，得到近似模糊 */
    private static Bitmap blur(Bitmap source, int downscale) {
        int w = Math.max(1, source.getWidth() / downscale);
        int h = Math.max(1, source.getHeight() / downscale);
        Bitmap small = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas smallCanvas = new Canvas(small);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        smallCanvas.drawBitmap(source, null, new RectF(0f, 0f, w, h), paint);
        Bitmap out = Bitmap.createBitmap(source.getWidth(), source.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.drawBitmap(small, null, new RectF(0f, 0f, out.getWidth(), out.getHeight()), paint);
        small.recycle();
        return out;
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private static int mix(int from, int to, float t) {
        float clamped = Math.max(0f, Math.min(1f, t));
        int a = Math.round(((from >> 24) & 0xFF) + (((to >> 24) & 0xFF) - ((from >> 24) & 0xFF)) * clamped);
        int r = Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * clamped);
        int g = Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * clamped);
        int b = Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * clamped);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}