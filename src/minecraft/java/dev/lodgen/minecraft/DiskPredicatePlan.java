package dev.lodgen.minecraft;

import dev.lodgen.mixin.CombiningPredicateAccess;
import dev.lodgen.mixin.NotPredicateAccess;
import dev.lodgen.mixin.MatchingBlocksPredicateAccess;
import dev.lodgen.mixin.StateTestingPredicateAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicateType;

import java.util.ArrayList;
import java.util.List;

/** Short-circuit disk targets without repeatedly reading the same neighbor.
 * Only vanilla, read-only predicates are compiled. States are read live;
 * explicit immutable block lists bypass registry-holder membership checks.
 * Tags keep the original evaluator so reloads remain visible.
 */
public final class DiskPredicatePlan {
    private static final int TRUE = -1, FALSE = -2;
    private static final int MAX_OFFSETS = 64, MAX_NODES = 256;
    private final Step[] steps;
    private final Vec3i[] offsets;
    private final int start;
    private final Step single;
    private DiskPredicatePlan nativeOrder;
    private BlockPredicate target;
    private java.util.function.Predicate<BlockState> centerGate;

    private DiskPredicatePlan(Builder builder, int start) {
        this.steps = builder.steps.toArray(Step[]::new);
        this.offsets = builder.offsets.toArray(Vec3i[]::new);
        this.start = start;
        this.single = steps.length == 1 && start == 0 ? steps[0] : null;
    }

    /** Null retains the original path for constant targets and custom predicates. */
    public static DiskPredicatePlan compile(BlockPredicate target) {
        Builder builder = new Builder(false);
        int start = builder.node(target, TRUE, FALSE, 0);
        if (builder.unsupported || builder.steps.isEmpty()) return null;
        var plan = new DiskPredicatePlan(builder, start);
        plan.target = target;
        var gate = centerGate(target);
        plan.centerGate = gate.test == ANY_STATE ? null : gate.test;
        Builder nativeBuilder = new Builder(true);
        int nativeStart = nativeBuilder.node(target, TRUE, FALSE, 0);
        if (nativeBuilder.reordered && !nativeBuilder.unsupported) {
            plan.nativeOrder = new DiskPredicatePlan(nativeBuilder, nativeStart);
        }
        return plan;
    }

    public BlockPredicate target() { return target; }

    /** A negative palette test proves that no column can match the target.
     * Probe the whole bounding square conservatively; writes/random providers
     * are untouched whenever any section could contain a match.
     */
    public boolean rejectsDisk(WorldGenLevel level, BlockPos from, BlockPos to, int halfHeight) {
        if (centerGate == null || halfHeight < 0 || halfHeight > 4
                || to.getX() < from.getX() || to.getZ() < from.getZ()
                || to.getX() - from.getX() > 16 || to.getZ() - from.getZ() > 16) return false;
        var reads = DiskBlockReads.local();
        if (!reads.matchesLevel(level)) return false;
        var position = reads.scratch.position;
        int lowY = from.getY() - halfHeight, highY = from.getY() + halfHeight;
        for (int cx = from.getX() >> 4; cx <= to.getX() >> 4; cx++) {
            for (int cz = from.getZ() >> 4; cz <= to.getZ() >> 4; cz++) {
                position.set(cx << 4, from.getY(), cz << 4);
                var chunk = reads.chunk(position);
                if (CaveMarkers.at(chunk, lowY) != null || CaveMarkers.at(chunk, highY) != null) return false;
                // A live LevelChunk may be edited by a player while its
                // neighboring feature runs. Only generation-owned chunks qualify.
                if (chunk.getClass() != ProtoChunk.class) return false;
                if ((chunk.isOutsideBuildHeight(lowY) || chunk.isOutsideBuildHeight(highY))
                        && centerGate.test(Blocks.VOID_AIR.defaultBlockState())) return false;
                var sections = chunk.getSections();
                int low = Math.max(0, chunk.getSectionIndex(lowY));
                int high = Math.min(sections.length - 1, chunk.getSectionIndex(highY));
                for (int index = low; index <= high; index++) {
                    // ProtoChunk normalizes completely empty sections to AIR,
                    // even if their palette contains CAVE_AIR or VOID_AIR.
                    if (sections[index].hasOnlyAir() ? centerGate.test(Blocks.AIR.defaultBlockState())
                            : sections[index].getStates().maybeHas(centerGate)) return false;
                }
            }
        }
        return true;
    }

    private static final java.util.function.Predicate<BlockState> ANY_STATE = state -> true;
    private static final java.util.function.Predicate<BlockState> NO_STATE = state -> false;
    private record Gate(java.util.function.Predicate<BlockState> test, boolean exact) {}

    /** Necessary center-state condition; non-center reads remain unknown.
     * Negation is usable only when its complete subtree reads the center.
     */
    private static Gate centerGate(BlockPredicate predicate) {
        var type = predicate.type();
        if (type == BlockPredicateType.TRUE) return new Gate(ANY_STATE, true);
        if (type == BlockPredicateType.NOT && predicate instanceof NotPredicateAccess not) {
            var child = centerGate(not.lodgen$predicate());
            return child.exact ? new Gate(child.test.negate(), true) : new Gate(ANY_STATE, false);
        }
        if ((type == BlockPredicateType.ALL_OF || type == BlockPredicateType.ANY_OF)
                && predicate instanceof CombiningPredicateAccess combining) {
            boolean all = type == BlockPredicateType.ALL_OF, exact = true;
            List<java.util.function.Predicate<BlockState>> tests = new ArrayList<>();
            for (var child : combining.lodgen$predicates()) {
                var gate = centerGate(child);
                exact &= gate.exact;
                if (gate.test == ANY_STATE) {
                    if (!all) return new Gate(ANY_STATE, false);
                } else tests.add(gate.test);
            }
            if (tests.isEmpty()) return new Gate(all ? ANY_STATE : NO_STATE, exact);
            if (tests.size() == 1) return new Gate(tests.getFirst(), exact);
            return new Gate(state -> {
                for (var test : tests) if (test.test(state) != all) return !all;
                return all;
            }, exact);
        }
        if (predicate instanceof StateTestingPredicateAccess state && state.lodgen$offset().equals(Vec3i.ZERO)) {
            return new Gate(state::lodgen$testState, true);
        }
        return new Gate(ANY_STATE, false);
    }

    public boolean test(WorldGenLevel level, BlockPos origin) {
        DiskBlockReads local = DiskBlockReads.local();
        DiskBlockReads reads = local.matchesLevel(level) ? local : null;
        return (reads != null && nativeOrder != null ? nativeOrder : this).test(level, origin, reads, local.scratch);
    }

    private boolean test(WorldGenLevel level, BlockPos origin, DiskBlockReads reads, Scratch scratch) {
        if (scratch.memoPlan != this) { scratch.resetMemo(); scratch.memoPlan = this; }
        if (single != null) {
            boolean result;
            if (single.worldPredicate != null) result = single.worldPredicate.test(level, origin);
            else {
                Vec3i offset = offsets[single.slot];
                scratch.position.set(origin.getX() + offset.getX(), origin.getY() + offset.getY(), origin.getZ() + offset.getZ());
                BlockState state = reads == null ? level.getBlockState(scratch.position) : reads.get(scratch.position);
                result = matches(scratch, single, 0, state);
            }
            return (result ? single.yes : single.no) == TRUE;
        }
        long visited = 0;
        try {
            int cursor = start;
            while (cursor >= 0) {
                Step step = steps[cursor];
                if (step.worldPredicate != null) {
                    cursor = step.worldPredicate.test(level, origin) ? step.yes : step.no;
                    continue;
                }
                long bit = 1L << step.slot;
                BlockState state;
                if ((visited & bit) == 0) {
                    Vec3i offset = offsets[step.slot];
                    scratch.position.set(origin.getX() + offset.getX(), origin.getY() + offset.getY(), origin.getZ() + offset.getZ());
                    state = reads == null ? level.getBlockState(scratch.position) : reads.get(scratch.position);
                    scratch.states[step.slot] = state;
                    visited |= bit;
                } else state = scratch.states[step.slot];
                cursor = matches(scratch, step, cursor, state) ? step.yes : step.no;
            }
            return cursor == TRUE;
        } finally {
            // Do not retain a modded state (or its registry) after a world closes.
            while (visited != 0) {
                scratch.states[Long.numberOfTrailingZeros(visited)] = null;
                visited &= visited - 1;
            }
        }
    }

    private static boolean matches(Scratch scratch, Step step, int index, BlockState state) {
        if (!step.memoize) return step.predicate.lodgen$testState(state);
        BlockState previous = scratch.memoStates[index];
        if (previous == state) return scratch.memoResults[index];
        boolean result = false;
        Block block = state.getBlock();
        for (Block candidate : step.blocks) {
            if (candidate == block) { result = true; break; }
        }
        if (previous == null) scratch.memoUsed[scratch.memoCount++] = index;
        scratch.memoStates[index] = state;
        scratch.memoResults[index] = result;
        return result;
    }

    private record Step(StateTestingPredicateAccess predicate, BlockPredicate worldPredicate, boolean memoize, Block[] blocks, int slot, int yes, int no) {}
    static final class Scratch {
        final BlockState[] states = new BlockState[MAX_OFFSETS];
        final BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        final BlockState[] memoStates = new BlockState[MAX_NODES];
        final boolean[] memoResults = new boolean[MAX_NODES];
        final int[] memoUsed = new int[MAX_NODES];
        int memoCount;
        DiskPredicatePlan memoPlan;
        void resetMemo() {
            while (memoCount > 0) memoStates[memoUsed[--memoCount]] = null;
            memoPlan = null;
        }
    }

    private static final class Builder {
        final List<Step> steps = new ArrayList<>();
        final List<Vec3i> offsets = new ArrayList<>();
        boolean unsupported;
        boolean reordered;
        final boolean nativeOrder;
        int nodes;
        Builder(boolean nativeOrder) { this.nativeOrder = nativeOrder; }

        int node(BlockPredicate predicate, int yes, int no, int depth) {
            if (++nodes > MAX_NODES || depth > 64) { unsupported = true; return no; }
            var type = predicate.type();
            if ((type == BlockPredicateType.ALL_OF || type == BlockPredicateType.ANY_OF)
                    && predicate instanceof CombiningPredicateAccess combining) {
                var children = combining.lodgen$predicates();
                boolean all = type == BlockPredicateType.ALL_OF;
                if (nativeOrder && all && children.size() > 1) {
                    var ordered = new ArrayList<>(children);
                    // Native regions own the center column. Rejecting its
                    // immutable state first avoids unnecessary neighbor reads.
                    // Custom world implementations retain the original order.
                    ordered.sort(java.util.Comparator.comparingInt(child ->
                            child instanceof StateTestingPredicateAccess state && state.lodgen$offset().equals(Vec3i.ZERO) ? 0 : 1));
                    if (!ordered.equals(children)) { children = ordered; reordered = true; }
                }
                int next = all ? yes : no;
                for (int i = children.size() - 1; i >= 0 && !unsupported; i--) {
                    next = node(children.get(i), all ? next : yes, all ? no : next, depth + 1);
                }
                return next;
            }
            if (type == BlockPredicateType.NOT && predicate instanceof NotPredicateAccess not) {
                return node(not.lodgen$predicate(), no, yes, depth + 1);
            }
            if (type == BlockPredicateType.TRUE) return yes;
            if (type == BlockPredicateType.HAS_STURDY_FACE) {
                int index = steps.size();
                steps.add(new Step(null, predicate, false, null, -1, yes, no));
                return index;
            }
            if ((type == BlockPredicateType.MATCHING_BLOCKS || type == BlockPredicateType.MATCHING_BLOCK_TAG
                    || type == BlockPredicateType.MATCHING_FLUIDS || type == BlockPredicateType.SOLID
                    || type == BlockPredicateType.REPLACEABLE)
                    && predicate instanceof StateTestingPredicateAccess state) {
                Vec3i offset = state.lodgen$offset();
                int slot = offsets.indexOf(offset);
                if (slot < 0) {
                    if (offsets.size() == MAX_OFFSETS) { unsupported = true; return no; }
                    slot = offsets.size();
                    offsets.add(new Vec3i(offset.getX(), offset.getY(), offset.getZ()));
                }
                int index = steps.size();
                // Explicit block lists depend only on the immutable state.
                // Neighbor reads remain live; tag predicates never memoize.
                boolean memoize = type == BlockPredicateType.MATCHING_BLOCKS && predicate instanceof MatchingBlocksPredicateAccess matching
                        && matching.lodgen$blocks() instanceof net.minecraft.core.HolderSet.Direct<?>;
                Block[] blocks = memoize ? ((MatchingBlocksPredicateAccess) predicate).lodgen$blocks().stream()
                        .map(net.minecraft.core.Holder::value).toArray(Block[]::new) : null;
                steps.add(new Step(state, null, memoize, blocks, slot, yes, no));
                return index;
            }
            unsupported = true;
            return no;
        }
    }
}
