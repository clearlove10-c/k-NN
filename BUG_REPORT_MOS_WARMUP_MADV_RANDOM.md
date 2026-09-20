# Bug Report: MOS warmup 在 MADV_RANDOM 映射上做顺序整读，冷启动 warmup 吞吐退化 20+ 倍

> 状态：待修复　|　组件：k-NN memory-optimized search (MOS) / warmup　|　严重级：性能（无正确性问题）
> 目标分支：dev/on-disk-rescore-optimize　|　报告日期：2026-09-18

## 1. 摘要

`FaissMemoryOptimizedSearcher.warmUp()` 复用**为 HNSW 随机搜索打开的** `IndexInput`（带 `DataAccessHint.RANDOM` hint）对整个 `.faiss` 文件做顺序整读 warmup。在 OpenSearch 生产链路中（`FsDirectoryFactory` 已启用 `MMapDirectory.ADVISE_BY_CONTEXT`），该 hint 会在 open 时对映射执行 `posix_madvise(MADV_RANDOM)`，内核对整个映射关闭 readahead——warmup 变成**逐 4KB 页的同步缺页**推进，实测吞吐 ≈ 磁盘单页 I/O 延迟倒数，比默认 readahead 慢 **20+ 倍**（10GB 文件：NVMe 分钟级 vs 秒级；HDD 小时级 vs 分钟级）。

次要点（P1，防御性）：`WarmupUtil.readAll(IndexInput)` 逐字节 `readByte()` 且丢弃返回值，存在被 JIT 死代码消除的实测风险；bulk 化仅带来 ~1.3 倍 CPU 收益，不是主要矛盾。

## 2. 环境

| 项 | 值 |
|---|---|
| OpenSearch | 3.9.0-SNAPSHOT（捆绑 Lucene **10.5.1**、JDK **Temurin 25.0.4.1**，FFM 最终版可用） |
| 触发条件 | `memory_optimized_search` 开启（faiss 引擎）+ 调用 k-NN warmup API，且目标 `.faiss` 文件冷（节点重启/换盘/failover 后） |
| 关键前提 | OpenSearch `FsDirectoryFactory.newFSDirectory()` 对 MMapDirectory 调用 `setReadAdvice(MMapDirectory.ADVISE_BY_CONTEXT)`（javap 证实，两处 case 分支均有）。**若目录是裸 `new MMapDirectory()`（如多数单测），hint 被完全忽略，无法复现**——这是该问题长期未被发现的原因 |

## 3. 根因调用链（全部经过代码/字节码级验证）

1. **k-NN 打开 .faiss 文件用 RANDOM hint（对搜索路径是正确且必要的）**
   - `src/main/java/org/opensearch/knn/index/codec/nativeindex/AbstractNativeEnginesKnnVectorsReader.java:59`
     `this.ioContext = state.context.withHints(FileTypeHint.DATA, FileDataHint.KNN_VECTORS, DataAccessHint.RANDOM);`
   - `FaissMemoryOptimizedSearcherFactory.java:38` 用该 context `directory.openInput(fileName, ioContext)`
2. **warmup 复用同一映射**：`FaissMemoryOptimizedSearcher.java:173-185` `warmUp()` 内 `indexInput.clone()` 后 `WarmupUtil.readAll(...)`。clone 共享同一组 `MemorySegment`（同一 VMA），advice 是 **per-VMA** 的，故 warmup 同样被 MADV_RANDOM 覆盖
3. **Lucene 10.5.1 侧映射逻辑**（javap 证实）：
   - `MMapDirectory.ADVISE_BY_CONTEXT`（`lambda$static$5`）：context 为 MERGE/FLUSH → `ReadAdvice.SEQUENTIAL`；否则 hints 含 `DataAccessHint.RANDOM` → `ReadAdvice.RANDOM`（Lucene 10.5 的 `IOContext$Context` 只剩 MERGE/FLUSH/DEFAULT，搜索路径必命中 RANDOM 分支）
   - ⚠️ `MMapDirectory` 构造器把 `readAdvice` 初始化为 `lambda$new$6` = **恒返回 `Optional.empty()`**；`lambda$openInput$7` 为 `readAdvice.apply(...).orElse(Constants.DEFAULT_READADVICE)` → **不调用 `setReadAdvice(ADVISE_BY_CONTEXT)` 时 hint 全部被忽略**（这就是第 2 节"单测复现不出来"的机制）
   - `MemorySegmentIndexInputProvider.map()` 在 **open 时**对每个 chunk：advice != NORMAL 且 `nativeAccess.filter(seg -> seg.address() % pageSize == 0)` 通过 → `NativeAccess.madvise(seg, advice)` → `PosixNativeAccess.posix_madvise(POSIX_MADV_RANDOM)`
4. **内核行为**：`MADV_RANDOM` 置 `VM_RAND_READ`，`do_sync_mmap_readahead()` 直接返回——无 readaround、无 readahead，每次缺页一个同步单页（4KB）I/O

## 4. 实证数据

### 4.1 单机隔离实验（64MB 文件冷读，Lucene 10.5.1 + JDK 25，`setReadAdvice(ADVISE_BY_CONTEXT)` 已启用）

| case | smaps VmFlags | 吞吐 | major faults |
|---|---|---|---|
| RANDOM hint + readByte()【现状】 | `rr` (MADV_RANDOM) | **15 MB/s** | **16384**（= 65536KB/4KB，逐页同步缺页） |
| RANDOM hint + readBytes(64KB) | `rr` | **15 MB/s** | 16326 |
| DEFAULT hint（无 advice）+ readByte() | 无 `rr`/`sr` | 312 MB/s | 1 |
| DEFAULT hint + readBytes(64KB) | 无 | 339 MB/s | 1 |
| SEQUENTIAL hint + readByte() | `sr` (MADV_SEQUENTIAL) | 334 MB/s | 1 |
| SEQUENTIAL hint + readBytes(64KB) | `sr` | **378 MB/s** | 1 |

### 4.2 磁盘特性交叉验证（同一块盘，dd iflag=direct）

- 顺序 1M 块读：~160 MB/s；单 4K 读：**~15.7 MB/s（≈250µs/IO）**
- **RANDOM 冷读 15 MB/s ≈ 精确等于磁盘单 4K 读速度** —— "单页缺页速度推进"的直接量化证据
- major-faults=16384 精确等于页数；readahead 启用后归 1（预取异步化，缺页全为 minor）

### 4.3 问题 2 的实测修正（warm 场景，64MB 全部命中 cache）

- `readByte()` 逐字节：17.9 GB/s；`readBytes(64KB)` bulk：22.8 GB/s —— **仅 1.3 倍差距**
- 结论：现代 JIT（final 方法单态内联 + 向量化）下逐字节并非严重瓶颈，10GB warm 场景差 ~0.5s 级别。**降级为 P1 防御性改进**，真实动机是消除 DCE 风险（见 §7）

## 5. 修复建议

### P0：warmup 使用专用 SEQUENTIAL-advised 流（核心修复，一处改动）

**设计要点**：page cache 是**文件级**共享的（与 VMA/advice 无关）。所以只需用带预读的映射把整个文件"摸"一遍，之后搜索路径的 RANDOM 映射全部命中 cache、零 I/O。**不要**试图在 warmup 里复用 clone 去解析 faiss 结构——直接把新流用于 `WarmupUtil.readAll` 整文件触碰即可，语义最简单。

改动点（`FaissMemoryOptimizedSearcher`）：

1. 构造函数增加 3 个参数：`Directory directory`、`String fileName`、`IOContext warmUpIoContext`（factory 处均已有）。`VectorSearcherFactory` 接口**不需要**改——`FaissMemoryOptimizedSearcherFactory.createVectorSearcher` 里已有 directory/fileName，warmup context 由 ioContext 推导
2. warmup context 的构造**必须从 `state.context` 直接派生**，不要从 `ioContext.withHints(...)` 链式派生（若 `withHints` 是追加语义会残留 RANDOM，`ADVISE_BY_CONTEXT` 的 RANDOM 分支优先级高于 SEQUENTIAL，会静默失效）：
   ```java
   // AbstractNativeEnginesKnnVectorsReader 构造器中：
   this.ioContext       = state.context.withHints(FileTypeHint.DATA, FileDataHint.KNN_VECTORS, DataAccessHint.RANDOM);
   this.warmUpIoContext = state.context.withHints(FileTypeHint.DATA, FileDataHint.KNN_VECTORS, DataAccessHint.SEQUENTIAL);
   ```
   并在单测中**断言** `warmUpIoContext.hints()` 含 SEQUENTIAL 且**不含** RANDOM
3. `FaissMemoryOptimizedSearcher.warmUp()` 新实现：
   ```java
   @Override
   public void warmUp() throws IOException {
       // 1) Graph (.faiss): dedicated readahead-friendly stream. Page cache is file-wide,
       //    so the RANDOM-advised search mapping hits cache afterwards with zero I/O.
       boolean graphWarmed = false;
       try (IndexInput seq = directory.openInput(fileName, warmUpIoContext)) {
           WarmupUtil.readAll(seq);
           graphWarmed = true;
       } catch (NoSuchFileException e) {
           log.debug("Warmup stream vanished for [{}], falling back to search input", fileName);
       }
       final IndexInput warmUpIndexInput = indexInput.clone();
       if (graphWarmed == false) {
           WarmupUtil.readAll(warmUpIndexInput);   // legacy fallback path
       }
       // 2) Flat vectors — keep as-is: for HNSW+flat it touches the same (now cached) pages
       //    cheaply; for SQ skip-storage it warms the separate .veq/.vec via Lucene reader.
       if (faissIndex.getVectorEncoding() == VectorEncoding.FLOAT32) {
           WarmupUtil.readAll(faissIndex.getFloatValues(warmUpIndexInput));
       } else if (faissIndex.getVectorEncoding() == VectorEncoding.BYTE) {
           WarmupUtil.readAll(faissIndex.getByteValues(warmUpIndexInput));
       }
   }
   ```
   `graphWarmed` 时跳过重复的 graph 逐字节遍历；flat-vector 遍历保留（可能覆盖 .faiss 之外的文件），此时纯 CPU（cache 命中，~18GB/s）
4. **hint 选型**：推荐 `SEQUENTIAL`（实验中吞吐最高）。注意 madvise(2) 对 MADV_SEQUENTIAL 有 drop-behind 语义（"may be released soon after they are accessed"），内存紧张的节点上理论上有轻微回吐风险；若要绝对保守可用 `NORMAL`（默认自适应 readahead，无 drop-behind，实测 339 MB/s 与 SEQUENTIAL 差距 <15%）。二选一均消除主要问题，**由实现者定夺，建议默认 SEQUENTIAL**
5. 修复天然覆盖两个调用方：`NativeEngines990KnnVectorsReader.warmUp()`（:284）与 `Faiss1040ScalarQuantizedKnnVectorsReader.warmUp()`（:133）都经 `memoryOptimizedSearcher.warmUp()` 进入，无需改 reader 层

### P1：`WarmupUtil.readAll(IndexInput)` bulk 化（防御性）

```java
public static void readAll(@NonNull final IndexInput indexInput) throws IOException {
    indexInput.seek(0);
    final byte[] buf = new byte[64 * 1024];
    for (long left = indexInput.length(); left > 0; ) {
        final int n = (int) Math.min(buf.length, left);
        indexInput.readBytes(buf, 0, n);
        left -= n;
    }
}
```
- 消除逐字节丢弃返回值被 JIT 消除的风险（`readBytes` 是真实 copy，无法消除）
- 该方法被所有 warmup 路径共用（graph、`.vec` slice、`.veq`），同步受益

## 6. 测试与性能对比计划（交给实现 agent 执行）

### 6.1 单元测试
- **新增**：mock/记录型 `Directory.openInput` 捕获 warmUp 期间传入的 `IOContext`，断言 hints = `{DATA, KNN_VECTORS, SEQUENTIAL}` 且不含 RANDOM；搜索路径 `openInput` 仍为 RANDOM（回归保护，factory 注释中的设计意图）
- **更新**：`WarmupUtilTests`（mock 校验从 `readByte` 调用次数改为 `readBytes` 分块）；检查 `RaisingIOExceptionIndexInput` 等测试桩是否实现了 `readBytes`
- 回归跑 `FaissMemoryOptimizedSearcherTests` / `FaissIndexFloatFlatTests` / `FaissHNSWTests` 等现有 MOS 测试

### 6.2 单机基准（harness 全量源码见附录 A，工作区切换后需重建于 `build/warmup-verify/`）
- 编译/运行（**必须 JDK 22+，生产用捆绑 JDK 25**；JDK 21 上 FFM 路径不同，结果无参考价值）：
  ```bash
  JDK=<opensearch-dist>/jdk/bin/javac && $JDK -cp lucene-core-10.5.1.jar ...
  java --enable-native-access=ALL-UNNAMED \
       --add-opens java.base/sun.nio.ch=ALL-UNNAMED --add-opens java.base/java.io=ALL-UNNAMED \
       -cp .:lucene-core-10.5.1.jar WarmupRepro 1024 data
  ```
- 对比矩阵：{修复前(RANDOM), 修复后(SEQUENTIAL)} × {1GB, 10GB} 冷读，指标：耗时、MB/s、major faults、smaps VmFlags（`rr`→`sr`）、吞吐是否 ≈ 设备顺序带宽
- **验收标准**：修复后冷 warmup 的 major faults ≈ 1（而非 size/4KB），吞吐达到设备顺序带宽量级

### 6.3 E2E 集群对比（before/after 同机同盘）
1. 建数 GB MOS 索引（faiss + `memory_optimized_search=true`）
2. 驱逐页缓存：`vmtouch -e <shard>/*.faiss`，或 `sync; echo 3 > /proc/sys/vm/drop_caches`（容器内 /proc/sys 可能只读，用前者）
3. `time curl -XPOST 'localhost:9200/_plugins/_knn/warmup/<index>'`，同时 `iostat -x 1` 观察平均请求大小（修复前 ≈4KB/req，修复后 ≥128KB）
4. 修复前可零风险验证问题存在：`grep -B20 '\.faiss' /proc/<os_pid>/smaps | grep VmFlags` 应见 `rr`；`strace -f -p <os_pid> -e madvise` 可见 MADV_RANDOM

## 7. 回归风险与注意事项（实现者必读）

1. **搜索路径的 RANDOM hint 保持不动**——`FaissMemoryOptimizedSearcherFactory.java:22-25` 的注释是有意设计（HNSW 随机访问下 readahead 有害且污染 cache）
2. **不要从 `ioContext` 链式 `withHints` 派生 warmup context**（追加语义会残留 RANDOM 并静默失效，见 §5-P0-2）；必须从 `state.context` 直接构造 + 单测断言
3. **裸 `new MMapDirectory()` 不吃任何 hint**（默认 readAdvice 返回 empty）——写任何 mmap 相关单测必须 `setReadAdvice(MMapDirectory.ADVISE_BY_CONTEXT)`，否则测出"无差异"是假象
4. warmup 流 `NoSuchFileException` 必须回退旧路径（segment 可能刚被 merge 删除；旧输入仍持有 fd 故可用）
5. 微基准必须消费读取的字节，否则 readByte 循环会被 JIT 完全消除（实测出现过 0.00s/400GB/s 的假数据）；smaps VmFlags 中 `mr`/`ms` 是 VM_MAYREAD/MAYSHARE（人人都有），**`rr`/`sr` 才是 MADV_RANDOM/SEQUENTIAL**
6. JDK <22 或原生访问不可用时 Lucene 静默跳过 madvise——修复在该环境退化为与现状等价，无副作用

## 附录 A：单机复现 harness（WarmupRepro.java，随报告归档）

```java
import org.apache.lucene.store.DataAccessHint;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.MMapDirectory;

import java.io.IOException;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Random;

/**
 * Reproduces the k-NN MOS warmup issue in isolation with Lucene 10.5.1:
 *  1) DataAccessHint.RANDOM (how AbstractNativeEnginesKnnVectorsReader opens the .faiss file)
 *     vs SEQUENTIAL / DEFAULT (candidate fixes) for a full sequential warmup read.
 *  2) readByte() loop (WarmupUtil.readAll today) vs bulk readBytes().
 *
 * Run on JDK 22+ (production uses JDK 25) with:
 *   java --enable-native-access=ALL-UNNAMED \
 *        --add-opens java.base/sun.nio.ch=ALL-UNNAMED --add-opens java.base/java.io=ALL-UNNAMED \
 *        -cp .:lucene-core-10.5.1.jar WarmupRepro <sizeMB> <dataDir>
 */
public class WarmupRepro {
    private static final int POSIX_FADV_DONTNEED = 4;
    private static final MethodHandle FADVISE;

    static {
        MethodHandle h = null;
        try {
            Linker linker = Linker.nativeLinker();
            h = linker.downcallHandle(
                linker.defaultLookup().find("posix_fadvise").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
                    ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));
        } catch (Throwable ignored) {
        }
        FADVISE = h;
    }

    public static void main(String[] args) throws Throwable {
        long sizeMB = args.length > 0 ? Long.parseLong(args[0]) : 256;
        Path dir = Path.of(args.length > 1 ? args[1] : "data");
        Files.createDirectories(dir);
        Path file = dir.resolve("vectors-" + sizeMB + "mb.bin");
        long size = sizeMB * 1024L * 1024L;

        if (!Files.exists(file)) {
            System.out.println("== creating " + sizeMB + "MB file at " + file);
            try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                byte[] block = new byte[1 << 20];
                new Random(42).nextBytes(block);
                long written = 0;
                while (written < size) {
                    int n = (int) Math.min(block.length, size - written);
                    ch.write(ByteBuffer.wrap(block, 0, n));
                    written += n;
                }
                ch.force(true);
            }
        }

        MMapDirectory directory = new MMapDirectory(dir);
        // OpenSearch FsDirectoryFactory.newFSDirectory() does exactly this — without it
        // MMapDirectory ignores IOContext data-access hints entirely (default advice is empty).
        directory.setReadAdvice(MMapDirectory.ADVISE_BY_CONTEXT);

        IOContext random = IOContext.DEFAULT.withHints(DataAccessHint.RANDOM);   // current MOS behavior
        IOContext seq = IOContext.DEFAULT.withHints(DataAccessHint.SEQUENTIAL);  // fix option A
        IOContext dflt = IOContext.DEFAULT;                                      // fix option B (adaptive readahead)

        System.out.printf("%-36s %9s %10s %14s%n", "case", "seconds", "MB/s", "major-faults");
        for (int round = 1; round <= 2; round++) {
            System.out.println("-- round " + round);
            run(directory, file, random, false, "RANDOM hint + readByte() [today]");
            run(directory, file, random, true, "RANDOM hint + readBytes()");
            run(directory, file, dflt, false, "DEFAULT hint + readByte()");
            run(directory, file, dflt, true, "DEFAULT hint + readBytes()");
            run(directory, file, seq, false, "SEQUENTIAL hint + readByte()");
            run(directory, file, seq, true, "SEQUENTIAL hint + readBytes()");
        }
        System.out.println("done.");
    }

    private static void run(Directory dir, Path file, IOContext ctx, boolean bulk, String label) throws Throwable {
        evict(file);
        long m0 = majorFaults();
        IndexInput in = dir.openInput(file.getFileName().toString(), ctx);
        printMappingAdvice(file, label); // outside of timing
        long t0 = System.nanoTime();
        long blackhole = 0;
        try {
            in.seek(0);
            if (bulk) {
                byte[] buf = new byte[64 * 1024];
                for (long left = in.length(); left > 0; ) {
                    int n = (int) Math.min(buf.length, left);
                    in.readBytes(buf, 0, n);
                    blackhole ^= buf[0] ^ buf[n - 1];
                    left -= n;
                }
            } else {
                for (long left = in.length(); left > 0; --left) blackhole ^= in.readByte();
            }
        } finally {
            in.close();
        }
        if (blackhole == Long.MIN_VALUE) System.out.println("(unreachable)");
        long t1 = System.nanoTime();
        double sec = (t1 - t0) / 1e9;
        double mbs = (Files.size(file) / 1024.0 / 1024.0) / sec;
        System.out.printf("%-36s %9.2f %10.0f %14d%n", label, sec, mbs, majorFaults() - m0);
    }

    /** Evict the file's clean pages from page cache (no root needed). */
    private static void evict(Path file) throws Throwable {
        if (FADVISE != null) {
            try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
                int fd = rawFd(ch);
                int rc = (int) FADVISE.invokeExact(fd, 0L, 0L, POSIX_FADV_DONTNEED);
                if (rc != 0) throw new IOException("posix_fadvise rc=" + rc);
                return;
            } catch (Throwable t) {
                System.out.println("   (fadvise failed: " + t + "; falling back to drop_caches)");
            }
        }
        new ProcessBuilder("sh", "-c", "sync; echo 3 > /proc/sys/vm/drop_caches").inheritIO().start().waitFor();
    }

    private static int rawFd(FileChannel ch) throws ReflectiveOperationException {
        var fdField = ch.getClass().getDeclaredField("fd");
        fdField.setAccessible(true);
        Object fdObj = fdField.get(ch);
        var intField = fdObj.getClass().getDeclaredField("fd");
        intField.setAccessible(true);
        return intField.getInt(fdObj);
    }

    /** Field 12 (majflt) of /proc/self/stat; comm (field 2) may contain spaces, skip past last ')'. */
    private static long majorFaults() throws IOException {
        String stat = Files.readString(Path.of("/proc/self/stat"));
        String[] rest = stat.substring(stat.lastIndexOf(')') + 2).split(" ");
        return Long.parseLong(rest[9]);
    }

    /** Print the kernel VMA flags of the mmap'd region: 'rr' = MADV_RANDOM, 'sr' = MADV_SEQUENTIAL. */
    private static void printMappingAdvice(Path file, String label) throws IOException {
        List<String> lines = Files.readAllLines(Path.of("/proc/self/smaps"));
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).endsWith(file.toString())) {
                for (int j = i + 1; j < Math.min(i + 30, lines.size()); j++) {
                    if (lines.get(j).startsWith("VmFlags:")) {
                        String flags = lines.get(j).replace("VmFlags:", "").trim();
                        String advice = flags.contains("rr") ? "MADV_RANDOM(rr)"
                            : flags.contains("sr") ? "MADV_SEQUENTIAL(sr)" : "NORMAL(no advice)";
                        System.out.println("   [" + label + "] kernel advice = " + advice);
                        return;
                    }
                }
            }
        }
        System.out.println("   [" + label + "] mapping NOT FOUND in smaps");
    }
}
```

编译：`javac -cp lucene-core-10.5.1.jar WarmupRepro.java`（lucene-core 可从 `~/.gradle/caches/modules-2/files-2.1/org.apache.lucene/lucene-core/10.5.1/` 取）。
