package com.aifashionvault.generation.vton;

public sealed interface InputSizeLimit permits InputSizeLimit.Known, InputSizeLimit.Unknown {
    static InputSizeLimit unknown() { return Unknown.INSTANCE; }

    record Known(long maxAssetBytes) implements InputSizeLimit {
        public Known {
            if (maxAssetBytes < 1) throw new IllegalArgumentException("maximum asset bytes must be positive");
        }
    }

    final class Unknown implements InputSizeLimit {
        private static final Unknown INSTANCE = new Unknown();
        private Unknown() { }
        @Override public boolean equals(Object other) { return other instanceof Unknown; }
        @Override public int hashCode() { return Unknown.class.hashCode(); }
        @Override public String toString() { return "Unknown"; }
    }
}
