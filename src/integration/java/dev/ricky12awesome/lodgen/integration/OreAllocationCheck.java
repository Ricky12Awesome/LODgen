package dev.ricky12awesome.lodgen.integration;

import dev.ricky12awesome.lodgen.LodgenConfig;
import dev.ricky12awesome.lodgen.minecraft.OreBlockAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.OreFeature;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockMatchTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.RandomBlockMatchTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.RuleTest;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Function;

/** Compare cursor ordering, live native reads and random-consuming ore rules. */
public final class OreAllocationCheck {
    public static void run(ServerLevel level) throws Exception {
        try (var access = new OreBlockAccess(level)) {
            var lists = List.of(List.of(), List.of("first"), List.of("first", "second"), List.of("a", "b", "c", "d"));
            for (var list : lists) {
                for (int prefix = 0; prefix <= list.size(); prefix++) {
                    var partial = access.targets(list);
                    for (int i = 0; i < prefix; i++) require(partial.next().equals(list.get(i)), "Ore prefix changed");
                    var actual = access.targets(list);
                    var expected = list.iterator();
                    while (expected.hasNext()) require(actual.hasNext() && actual.next().equals(expected.next()), "Ore target order changed");
                    require(!actual.hasNext(), "Extra ore target");
                    try { actual.next(); throw new AssertionError("Exhausted ore iterator returned a target"); }
                    catch (NoSuchElementException expectedFailure) { /* Matches immutable List.iterator(). */ }
                    require(access.targets(list) == actual, "Ore cursor was not reused");
                }
            }
            var mutable = new ArrayList<>(List.of("first", "second"));
            Iterator<?> original = access.targets(mutable);
            require(access.targets(mutable) != original, "Mutable ore targets lost their original iterator");
            mutable.add("third");
            try { original.next(); throw new AssertionError("Mutable ore iterator lost its fail-fast behavior"); }
            catch (java.util.ConcurrentModificationException expectedFailure) { /* Original iterator. */ }

            Function<BlockPos, BlockState> originalReader = access::getBlockState;
            var position = new BlockPos.MutableBlockPos().set(0, 64, 0);
            for (int y = -66; y <= 321; y += 7) {
                position.set(0, y, 0);
                require(access.apply(position) == originalReader.apply(position), "Ore native reader changed");
            }

            // Resolve the folded 26.3 configuration and legacy named/intermediary
            // signatures by their common argument types, rather than method names.
            var canPlace = java.util.Arrays.stream(OreFeature.class.getMethods()).filter(method ->
                    method.getReturnType() == boolean.class && method.getParameterCount() >= 5
                            && method.getParameterTypes()[0] == BlockState.class
                            && method.getParameterTypes()[1] == Function.class).findFirst().orElseThrow();
            var types = canPlace.getParameterTypes();
            boolean folded = types.length == 5;
            Class<?> targetType = types[folded ? 3 : 4];
            var targetConstructor = targetType.getDeclaredConstructor(RuleTest.class, BlockState.class);
            targetConstructor.setAccessible(true);
            for (RuleTest rule : List.of(new BlockMatchTest(Blocks.STONE), new RandomBlockMatchTest(Blocks.STONE, 0.5F))) {
                Object target = targetConstructor.newInstance(rule, Blocks.IRON_ORE.defaultBlockState());
                var targetList = List.of(target);
                for (float discard : new float[]{0, 0.25F, 1}) {
                    Object owner = folded ? OreFeature.class.getConstructor(List.class, int.class, float.class).newInstance(targetList, 8, discard)
                            : types[3].getConstructor(List.class, int.class, float.class).newInstance(targetList, 8, discard);
                    for (int seed = 0; seed < 128; seed++) {
                        RandomSource vanilla = RandomSource.create(seed), optimized = RandomSource.create(seed);
                        BlockState state = (seed % 3 == 0 ? Blocks.AIR : Blocks.STONE).defaultBlockState();
                        position.set(0, 64 + seed % 8, 0);
                        Object expected = folded ? canPlace.invoke(owner, state, originalReader, vanilla, target, position)
                                : canPlace.invoke(null, state, originalReader, vanilla, owner, target, position);
                        Object actual = folded ? canPlace.invoke(owner, state, access, optimized, target, position)
                                : canPlace.invoke(null, state, access, optimized, owner, target, position);
                        require(expected.equals(actual) && vanilla.nextLong() == optimized.nextLong(), "Ore reader changed a rule or its random draws");
                    }
                }
            }
        }
        LodgenConfig.LOGGER.info("PASS: reusable ore targets, mutable-list fallback, native reads and 768 ore rule/random comparisons");
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
