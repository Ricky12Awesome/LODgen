package dev.ricky12awesome.lodgen.integration;

import dev.ricky12awesome.lodgen.minecraft.DiskPredicatePlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import net.minecraft.world.level.material.Fluids;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/** Test transformed predicates against the vanilla evaluator, including writes
 * between tests, short-circuit reads, unknown predicates and concurrent use.
 */
public final class DiskPredicateCheck {
    private static final java.util.concurrent.atomic.AtomicLong COMPARED = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong SKIPPED_DISKS = new java.util.concurrent.atomic.AtomicLong();
    public static boolean verify(boolean actual, BlockPredicate target, Object region, Object position) {
        boolean expected = target.test((WorldGenLevel) region, (BlockPos) position);
        require(actual == expected, "Disk target changed at " + position + " (" + target.type() + ")");
        COMPARED.incrementAndGet();
        return actual;
    }
    public static long compared() { return COMPARED.get(); }
    public static long skippedDisks() { return SKIPPED_DISKS.get(); }
    public static Iterable<BlockPos> verifyColumns(Iterable<BlockPos> actual, BlockPos from, BlockPos to,
            WorldGenLevel level, Object owner, int halfHeight) {
        if (!(actual instanceof java.util.List<?> list) || !list.isEmpty()) return actual;
        SKIPPED_DISKS.incrementAndGet();
        var target = ((dev.ricky12awesome.lodgen.minecraft.DiskPredicateAccess) owner).lodgen$targetPlan().target();
        int centerX = (from.getX() + to.getX()) / 2, centerZ = (from.getZ() + to.getZ()) / 2;
        int radius = (to.getX() - from.getX()) / 2;
        var position = new BlockPos.MutableBlockPos();
        for (int x = from.getX(); x <= to.getX(); x++) for (int z = from.getZ(); z <= to.getZ(); z++) {
            int dx = x - centerX, dz = z - centerZ;
            if (dx * dx + dz * dz > radius * radius) continue;
            for (int y = from.getY() + halfHeight; y >= from.getY() - halfHeight; y--) {
                position.set(x, y, z);
                verify(false, target, level, position);
            }
        }
        return actual;
    }
    public static void run() throws Exception {
        Vec3i above = new Vec3i(0, 1, 0), east = new Vec3i(1, 0, 0);
        var leaves = List.of(BlockPredicate.matchesBlocks(Blocks.AIR),
                BlockPredicate.matchesBlocks(above, List.of(Blocks.STONE, Blocks.DIRT)),
                BlockPredicate.matchesFluids(Fluids.WATER), BlockPredicate.matchesTag(east, BlockTags.LOGS),
                BlockPredicate.solid(), BlockPredicate.replaceable());
        var random = new Random(123456789);
        List<BlockPredicate> targets = new ArrayList<>();
        for (int i = 0; i < 100; i++) targets.add(tree(random, leaves, 4));
        // Deliberately repeat reads at offset zero; compilation must succeed.
        var repeated = BlockPredicate.allOf(BlockPredicate.matchesBlocks(Blocks.STONE),
                BlockPredicate.not(BlockPredicate.matchesBlocks(Blocks.DIRT)));
        var plan = DiskPredicatePlan.compile(repeated);
        require(plan != null, "Repeated disk target did not compile");
        var reader = new Reader();
        var origin = new BlockPos(-17, 70, 33);
        reader.states.put(origin, Blocks.STONE.defaultBlockState());
        require(plan.test(reader.level, origin) && reader.reads.size() == 1, "Repeated offset was read twice");
        reader.states.put(origin, Blocks.DIRT.defaultBlockState());
        reader.reads.clear();
        require(!plan.test(reader.level, origin) && reader.reads.size() == 1, "Feature write was hidden by a stale cache");
        reader.reads.clear();
        require(!repeated.test(reader.level, origin) && reader.reads.size() == 1, "Vanilla fixture mismatch");
        var guarded = BlockPredicate.allOf(BlockPredicate.matchesBlocks(Blocks.STONE),
                BlockPredicate.matchesBlocks(above, List.of(Blocks.AIR)), BlockPredicate.matchesBlocks(Blocks.STONE));
        var guardedPlan = DiskPredicatePlan.compile(guarded);
        reader.reads.clear();
        require(guardedPlan != null && !guardedPlan.test(reader.level, origin) && reader.reads.size() == 1,
                "Short-circuit read order changed");
        var unknown = BlockPredicate.allOf(repeated, BlockPredicate.insideWorld(Vec3i.ZERO));
        require(DiskPredicatePlan.compile(unknown) == null, "World-sensitive predicate compiled");
        var mixed = BlockPredicate.allOf(repeated, BlockPredicate.hasSturdyFace(Direction.UP));
        var mixedPlan = DiskPredicatePlan.compile(mixed);
        reader.states.put(origin, Blocks.STONE.defaultBlockState());
        require(mixedPlan != null && mixedPlan.test(reader.level, origin) == mixed.test(reader.level, origin),
                "Sturdy-face predicate lost its original world context");
        require(DiskPredicatePlan.compile(BlockPredicate.alwaysTrue()) == null, "Constant target was slowed down");
        var pool = Executors.newFixedThreadPool(4);
        try {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int worker = 0; worker < 4; worker++) {
                int seed = worker;
                futures.add(CompletableFuture.runAsync(() -> compare(targets, seed), pool));
            }
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        } finally { pool.shutdownNow(); }
        dev.ricky12awesome.lodgen.LodgenConfig.LOGGER.info("PASS: disk predicate truth tables, short-circuit reads, writes, fallback and concurrent evaluation");
    }

    private static void compare(List<BlockPredicate> targets, int seed) {
        var reader = new Reader();
        var random = new Random(seed);
        BlockState[] states = {Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState(),
                Blocks.DIRT.defaultBlockState(), Blocks.WATER.defaultBlockState(), Blocks.OAK_LOG.defaultBlockState()};
        var origin = new BlockPos(-17, 70, 33);
        for (BlockPredicate target : targets) {
            var plan = DiskPredicatePlan.compile(target);
            if (plan == null) continue;
            java.util.function.Predicate<BlockState> center;
            try {
                var field = DiskPredicatePlan.class.getDeclaredField("centerGate"); field.setAccessible(true);
                @SuppressWarnings("unchecked") var value = (java.util.function.Predicate<BlockState>) field.get(plan);
                center = value;
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
            for (int sample = 0; sample < 100; sample++) {
                reader.states.put(origin, states[random.nextInt(states.length)]);
                reader.states.put(origin.above(), states[random.nextInt(states.length)]);
                reader.states.put(origin.east(), states[random.nextInt(states.length)]);
                reader.reads.clear();
                boolean expected = target.test(reader.level, origin);
                require(!expected || center == null || center.test(reader.states.get(origin)),
                        "Palette prefilter rejected a matching center state");
                var expectedOrder = reader.reads.stream().distinct().toList();
                reader.reads.clear();
                require(plan.test(reader.level, origin) == expected, "Compiled predicate changed its truth table");
                require(reader.reads.equals(expectedOrder), "Compiled predicate changed short-circuit order or repeated a lookup");
            }
        }
    }

    private static BlockPredicate tree(Random random, List<BlockPredicate> leaves, int depth) {
        if (depth == 0) return leaves.get(random.nextInt(leaves.size()));
        return switch (random.nextInt(4)) {
            case 0 -> BlockPredicate.not(tree(random, leaves, depth - 1));
            case 1 -> BlockPredicate.allOf(tree(random, leaves, depth - 1), tree(random, leaves, depth - 1));
            case 2 -> BlockPredicate.anyOf(tree(random, leaves, depth - 1), tree(random, leaves, depth - 1));
            default -> BlockPredicate.allOf(BlockPredicate.alwaysTrue(), tree(random, leaves, depth - 1),
                    BlockPredicate.anyOf(List.of()));
        };
    }

    private static final class Reader {
        final Map<BlockPos, BlockState> states = new HashMap<>();
        final List<BlockPos> reads = new ArrayList<>();
        final WorldGenLevel level = (WorldGenLevel) Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(),
                new Class<?>[]{WorldGenLevel.class}, (proxy, method, args) -> {
                    if (method.getReturnType() != BlockState.class || method.getParameterCount() != 1
                            || method.getParameterTypes()[0] != BlockPos.class) throw new AssertionError("Unexpected world call " + method);
                    BlockPos pos = ((BlockPos) args[0]).immutable();
                    reads.add(pos);
                    return states.getOrDefault(pos, Blocks.AIR.defaultBlockState());
                });
    }
    private static void require(boolean success, String message) { if (!success) throw new AssertionError(message); }
    private DiskPredicateCheck() {}
}
