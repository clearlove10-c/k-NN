/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.knn.memoryoptsearch;

import org.apache.lucene.codecs.hnsw.FlatVectorsReader;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;

import java.io.IOException;

/**
 * Factory to create {@link VectorSearcher}.
 * Provided parameters will have {@link Directory} and a file name where implementation can rely on it to open an input stream.
 */
public interface VectorSearcherFactory {

    /**
     * Create a non-null {@link VectorSearcher} with given Lucene's {@link Directory}.
     * <p>
     * The {@code flatVectorsReader} provides access to Lucene's flat vector storage. For Faiss SQ (for 1 bit) indices
     * where Faiss skips flat storage via {@code IO_FLAG_SKIP_STORAGE}, the reader is used to wire in
     * a {@code FaissScalarQuantizedFlatIndex} backed by Lucene's quantized reader.
     *
     * @param directory Lucene's Directory.
     * @param fileName Logical file name to load.
     * @param fieldInfo Field info containing metadata for ADC extraction
     * @param ioContext IOContext to use when opening the file
     * @param flatVectorsReader Reader providing flat vector scoring and storage
     * @return Null instance if it is not supported, otherwise return {@link VectorSearcher}
     * @throws IOException
     */
    VectorSearcher createVectorSearcher(
        Directory directory,
        String fileName,
        FieldInfo fieldInfo,
        IOContext ioContext,
        FlatVectorsReader flatVectorsReader
    ) throws IOException;

    /**
     * Create a non-null {@link VectorSearcher} with given Lucene's {@link Directory}, additionally
     * providing an {@link IOContext} dedicated to warmup.
     * <p>
     * The {@code warmUpIoContext} is expected to carry a {@link org.apache.lucene.store.DataAccessHint#SEQUENTIAL}
     * data-access hint so that implementations can open a separate readahead-friendly stream over the same
     * file for warmup, instead of reusing the {@code ioContext} mapping (which is typically
     * {@link org.apache.lucene.store.DataAccessHint#RANDOM}-advised and disables kernel readahead).
     * <p>
     * The default implementation ignores the warmup context and falls back to the legacy signature,
     * preserving behavior for factories that do not support a dedicated warmup stream.
     *
     * @param directory Lucene's Directory.
     * @param fileName Logical file name to load.
     * @param fieldInfo Field info containing metadata for ADC extraction
     * @param ioContext IOContext to use when opening the file for search
     * @param warmUpIoContext IOContext carrying the SEQUENTIAL data-access hint to use when the
     *                        searcher opens a dedicated warmup stream; may be ignored by the
     *                        implementation
     * @param flatVectorsReader Reader providing flat vector scoring and storage
     * @return Null instance if it is not supported, otherwise return {@link VectorSearcher}
     * @throws IOException if an I/O error occurs
     */
    default VectorSearcher createVectorSearcher(
        Directory directory,
        String fileName,
        FieldInfo fieldInfo,
        IOContext ioContext,
        IOContext warmUpIoContext,
        FlatVectorsReader flatVectorsReader
    ) throws IOException {
        return createVectorSearcher(directory, fileName, fieldInfo, ioContext, flatVectorsReader);
    }
}
