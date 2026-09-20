/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.knn.index.util;

import lombok.NonNull;
import lombok.experimental.UtilityClass;
import org.apache.lucene.codecs.lucene95.HasIndexSlice;
import org.apache.lucene.index.ByteVectorValues;
import org.apache.lucene.index.FloatVectorValues;
import org.apache.lucene.store.IndexInput;

import java.io.IOException;

/**
 * Utility for warming up vector data by reading it into the OS page cache.
 * <p>
 * Each {@code readAll} overload forces the underlying bytes into memory so that subsequent
 * searches avoid cold-read latency. When the vector values implement {@link HasIndexSlice},
 * the warmup reads directly from the backing {@link IndexInput} slice; otherwise it iterates
 * through every vector value individually.
 */
@UtilityClass
public class WarmupUtil {
    /**
     * Warms up float vector data by reading all underlying bytes into the page cache.
     * <p>
     * If {@code floatVectorValues} implements {@link HasIndexSlice}, the warmup is performed
     * by reading the raw bytes from the index slice. Otherwise, each vector is accessed
     * individually via {@link FloatVectorValues#vectorValue(int)}.
     *
     * @param floatVectorValues the float vector values to warm up
     * @throws IOException if an I/O error occurs during reading
     */
    public static void readAll(@NonNull final FloatVectorValues floatVectorValues) throws IOException {
        if (floatVectorValues instanceof HasIndexSlice hasIndexSlice) {
            final IndexInput slice = hasIndexSlice.getSlice();
            if (slice != null) {
                readAll(slice);
                return;
            }
        }
        for (int i = 0; i < floatVectorValues.size(); ++i) {
            floatVectorValues.vectorValue(i);
        }
    }

    /**
     * Warms up byte vector data by reading all underlying bytes into the page cache.
     * <p>
     * If {@code byteVectorValues} implements {@link HasIndexSlice}, the warmup is performed
     * by reading the raw bytes from the index slice. Otherwise, each vector is accessed
     * individually via {@link ByteVectorValues#vectorValue(int)}.
     *
     * @param byteVectorValues the byte vector values to warm up
     * @throws IOException if an I/O error occurs during reading
     */
    public static void readAll(@NonNull final ByteVectorValues byteVectorValues) throws IOException {
        if (byteVectorValues instanceof HasIndexSlice hasIndexSlice) {
            final IndexInput slice = hasIndexSlice.getSlice();
            if (slice != null) {
                readAll(slice);
                return;
            }
        }
        for (int i = 0; i < byteVectorValues.size(); ++i) {
            byteVectorValues.vectorValue(i);
        }
    }

    /**
     * Size of the buffer used to bulk-read the index input during warmup.
     */
    private static final int READ_ALL_BUFFER_SIZE = 64 * 1024;

    /**
     * Warms up an {@link IndexInput} by sequentially reading every byte from the beginning.
     * <p>
     * The read is performed in bulk chunks via {@link IndexInput#readBytes(byte[], int, int)}.
     * A bulk copy cannot be eliminated by the JIT as dead code (unlike a {@code readByte()}
     * loop that discards the returned value), and it touches the same pages, so the page-cache
     * warming effect is identical while being friendlier to the CPU.
     *
     * @param indexInput the index input to warm up
     * @throws IOException if an I/O error occurs during reading
     */
    public static void readAll(@NonNull final IndexInput indexInput) throws IOException {
        indexInput.seek(0);
        final byte[] buffer = new byte[READ_ALL_BUFFER_SIZE];
        for (long left = indexInput.length(); left > 0;) {
            final int chunk = (int) Math.min(buffer.length, left);
            indexInput.readBytes(buffer, 0, chunk);
            left -= chunk;
        }
    }
}
