package com.aifashionvault.generation.vton;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.OptionalLong;

public interface ProviderMediaSource extends AutoCloseable {
    InputStream stream();
    OptionalLong contentLength();
    long maxBytes();
    String mediaType();

    @Override void close() throws IOException;

    static ProviderMediaSource of(InputStream stream, OptionalLong contentLength, long maxBytes, String mediaType) {
        if (stream == null) throw new IllegalArgumentException("media stream is required");
        if (contentLength == null) throw new IllegalArgumentException("content length knowledge is required");
        if (maxBytes < 1) throw new IllegalArgumentException("maximum bytes must be positive");
        if (contentLength.isPresent() && (contentLength.getAsLong() < 0 || contentLength.getAsLong() > maxBytes)) throw new IllegalArgumentException("content length exceeds media bound");
        if (mediaType == null || !mediaType.strip().toLowerCase(java.util.Locale.ROOT).startsWith("image/")) throw new IllegalArgumentException("image media type is required");
        String normalizedMediaType = mediaType.strip().toLowerCase(java.util.Locale.ROOT);
        InputStream bounded = new FilterInputStream(stream) {
            private long remaining = maxBytes;

            @Override public int read() throws IOException {
                if (remaining == 0) return endOrOverflow();
                int value = super.read();
                if (value >= 0) remaining--;
                return value;
            }

            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                if (length == 0) return 0;
                if (remaining == 0) return endOrOverflow();
                int count = super.read(bytes, offset, (int) Math.min(length, remaining));
                if (count > 0) remaining -= count;
                return count;
            }

            private int endOrOverflow() throws IOException {
                if (super.read() < 0) return -1;
                throw new IOException("provider output exceeds declared media bound");
            }
        };
        return new ProviderMediaSource() {
            @Override public InputStream stream() { return bounded; }
            @Override public OptionalLong contentLength() { return contentLength; }
            @Override public long maxBytes() { return maxBytes; }
            @Override public String mediaType() { return normalizedMediaType; }
            @Override public void close() throws IOException { bounded.close(); }
        };
    }
}
