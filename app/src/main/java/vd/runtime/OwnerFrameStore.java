package vd.runtime;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.media.Image;
import android.media.ImageReader;

/**
 * Retains one newest native image. Idle callbacks only acquire/replace/close images; no full-frame
 * heap allocation or pixel copy occurs until an uncached PNG is requested. Three reader slots
 * allow acquireLatestImage to drain older buffers while one image is retained for static pages.
 * There is no idle timer and a snapshot never depends on the producer drawing another frame.
 */
final class OwnerFrameStore implements ImageReader.OnImageAvailableListener {
    static final class Snapshot {
        final byte[] png;
        final int width;
        final int height;
        final long timestampNs;
        final int frameCount;

        Snapshot(OwnerFramePolicy.Snapshot snapshot) {
            png = snapshot.png;
            width = snapshot.width;
            height = snapshot.height;
            timestampNs = snapshot.timestampNs;
            frameCount = snapshot.frameCount;
        }
    }

    private final Object acquisitionLock = new Object();
    private final OwnerFramePolicy frames = new OwnerFramePolicy();

    @Override
    public void onImageAvailable(ImageReader reader) {
        refresh(reader);
    }

    /** Also called immediately before a snapshot, even if its image callback is still queued. */
    void refresh(ImageReader reader) {
        synchronized (acquisitionLock) {
            if (frames.isClosed()) return;
            Image image = null;
            try {
                image = reader.acquireLatestImage();
                if (image == null) return;
                Image.Plane[] planes = image.getPlanes();
                if (image.getFormat() != PixelFormat.RGBA_8888 || planes.length != 1) return;
                Image.Plane plane = planes[0];
                ByteBuffer buffer = plane.getBuffer();
                int width = image.getWidth();
                int height = image.getHeight();
                int rowStride = plane.getRowStride();
                int pixelStride = plane.getPixelStride();
                OwnerRgbaCopy.byteCount(buffer, width, height, pixelStride, rowStride);
                frames.replace(new PlaneFrame(image, buffer, width, height, rowStride, pixelStride));
                image = null; // Ownership transferred; replacing/clearing closes it exactly once.
            } catch (Throwable ignored) {
                // A malformed/dropped frame leaves the previous valid native image available.
            } finally {
                if (image != null) {
                    try { image.close(); } catch (Throwable ignored) { /* Already closed. */ }
                }
            }
        }
    }

    boolean hasFrame() { return frames.hasFrame(); }
    int frameCount() { return frames.frameCount(); }
    long latestTimestampNs() { return frames.timestampNs(); }
    int frameWidth() { return frames.width(); }
    int frameHeight() { return frames.height(); }

    /** Terminal: queued callbacks and encoders can never repopulate this store after release. */
    void clear() {
        synchronized (acquisitionLock) {
            frames.close();
        }
    }

    /** PNG bytes and metadata always belong to the same captured image, including cache hits. */
    /** 缩略帧：微缩镜/装裱视窗用，低分辨率 JPEG，字节数远小于全分辨率 PNG。 */
    Snapshot encodeThumb(int maxBytes) throws OwnerException {
        try {
            OwnerFramePolicy.Snapshot snapshot = frames.snapshot(THUMB_JPEG_ENCODER);
            if (snapshot == null) {
                throw new OwnerException(OwnerProtocol.ERROR_NO_FRAME, "no frame");
            }
            if (snapshot.png.length > maxBytes) {
                throw new OwnerException(OwnerProtocol.ERROR_IMAGE_TOO_LARGE,
                        "thumb " + snapshot.png.length);
            }
            return new Snapshot(snapshot);
        } catch (OwnerException ex) {
            throw ex;
        } catch (Throwable ex) {
            throw new OwnerException(OwnerProtocol.ERROR_SNAPSHOT_FAILED, "thumb");
        }
    }

    Snapshot encodePng(int maxBytes) throws OwnerException {
        try {
            OwnerFramePolicy.Snapshot snapshot = frames.snapshot(PNG_ENCODER);
            if (snapshot == null) {
                throw new OwnerException(OwnerProtocol.ERROR_NO_FRAME, "no frame");
            }
            // A cache hit must still honor this request's byte budget.
            if (snapshot.png.length > maxBytes) {
                throw new OwnerException(OwnerProtocol.ERROR_IMAGE_TOO_LARGE,
                        "png " + snapshot.png.length);
            }
            return new Snapshot(snapshot);
        } catch (OwnerException ex) {
            throw ex;
        } catch (Throwable ex) {
            throw new OwnerException(OwnerProtocol.ERROR_SNAPSHOT_FAILED,
                    ex.getClass().getSimpleName());
        }
    }

    private static final OwnerFramePolicy.Encoder PNG_ENCODER = new OwnerFramePolicy.Encoder() {
        @Override
        public byte[] encode(byte[] rgba, int width, int height) {
            return encodeRgba(rgba, width, height, 0, Bitmap.CompressFormat.PNG, 100);
        }
    };

    /**
     * 缩略帧编码器：给胶囊微缩镜/装裱视窗用。
     *
     * 全分辨率 PNG 编码 + Base64 + 单行 JSON 是帧率上不去的根因（见 C 方案）。
     * 微缩镜实际只渲染 18x32dp，没必要传原图：先按长边缩到 [maxEdge]，
     * 再用 JPEG 低质量编码，单帧字节数降到 1/10 以下，往返才能提到每秒多帧。
     */
    private static final OwnerFramePolicy.Encoder THUMB_JPEG_ENCODER = new OwnerFramePolicy.Encoder() {
        @Override
        public byte[] encode(byte[] rgba, int width, int height) {
            return encodeRgba(rgba, width, height, THUMB_MAX_EDGE_PX, Bitmap.CompressFormat.JPEG, THUMB_JPEG_QUALITY);
        }
    };

    private static byte[] encodeRgba(byte[] rgba, int width, int height, int maxEdge,
                                     Bitmap.CompressFormat format, int quality) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        try {
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(rgba));
            Bitmap source = bitmap;
            Bitmap scaled = null;
            if (maxEdge > 0) {
                int longest = Math.max(width, height);
                if (longest > maxEdge) {
                    float ratio = (float) maxEdge / (float) longest;
                    int tw = Math.max(1, Math.round(width * ratio));
                    int th = Math.max(1, Math.round(height * ratio));
                    scaled = Bitmap.createScaledBitmap(bitmap, tw, th, true);
                    source = scaled;
                }
            }
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream(rgba.length / 8);
                if (!source.compress(format, quality, out)) {
                    throw new IllegalStateException("compress");
                }
                return out.toByteArray();
            } finally {
                if (scaled != null) scaled.recycle();
            }
        } finally {
            bitmap.recycle();
        }
    }

    /** 缩略帧长边上限：微缩镜 18x32dp 在 440dpi 下约 100px 宽，360 已足够清晰。 */
    static final int THUMB_MAX_EDGE_PX = 360;
    /** 缩略帧 JPEG 质量：视觉预览用途，60 在手机屏上几乎看不出差别。 */
    static final int THUMB_JPEG_QUALITY = 60;

    private static final class PlaneFrame implements OwnerFramePolicy.Frame {
        private final Image image;
        private final ByteBuffer buffer;
        private final int width;
        private final int height;
        private final int rowStride;
        private final int pixelStride;
        private final long timestampNs;

        PlaneFrame(Image image, ByteBuffer buffer, int width, int height, int rowStride,
                int pixelStride) {
            this.image = image;
            this.buffer = buffer;
            this.width = width;
            this.height = height;
            this.rowStride = rowStride;
            this.pixelStride = pixelStride;
            this.timestampNs = image.getTimestamp();
        }

        @Override public int width() { return width; }
        @Override public int height() { return height; }
        @Override public long timestampNs() { return timestampNs; }
        @Override public byte[] copyRgba() {
            return OwnerRgbaCopy.copy(buffer, width, height, pixelStride, rowStride);
        }
        @Override public void close() { image.close(); }
    }
}
