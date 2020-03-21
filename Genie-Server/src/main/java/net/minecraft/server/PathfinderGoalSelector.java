package net.minecraft.server;

import com.tuinity.tuinity.util.OptimizedSmallEnumSet;
import com.google.common.collect.Sets;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Iterator; // Tuinity
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class PathfinderGoalSelector {

    private static final Logger LOGGER = LogManager.getLogger();
    private static final PathfinderGoalWrapped b = new PathfinderGoalWrapped(Integer.MAX_VALUE, new PathfinderGoal() {
        @Override
        public boolean a() {
            return false;
        }
    }) {
        @Override
        public boolean g() {
            return false;
        }
    };
    private final Map<PathfinderGoal.Type, PathfinderGoalWrapped> c = new EnumMap(PathfinderGoal.Type.class);
    private final Set<PathfinderGoalWrapped> d = Sets.newLinkedHashSet();private Set<PathfinderGoalWrapped> getTasks() { return d; }// Paper - OBFHELPER
    private final GameProfilerFiller e;
    private final OptimizedSmallEnumSet<PathfinderGoal.Type> goalTypes = new OptimizedSmallEnumSet<>(PathfinderGoal.Type.class); // Tuinity - reduce garbage on heap
    private int g = 3;private int getTickRate() { return g; } // Paper - OBFHELPER
    private int curRate;private int getCurRate() { return curRate; } private void incRate() { this.curRate++; } // Paper TODO

    public PathfinderGoalSelector(GameProfilerFiller gameprofilerfiller) {
        this.e = gameprofilerfiller;
    }

    public void a(int i, PathfinderGoal pathfindergoal) {
        this.d.add(new PathfinderGoalWrapped(i, pathfindergoal));
    }

    // Paper start
    public boolean inactiveTick() {
        if (getCurRate() % getTickRate() != 0) {
            incRate();
            return false;
        } else {
            return true;
        }
    }
    public boolean hasTasks() {
        for (PathfinderGoalWrapped task : getTasks()) {
            if (task.isRunning()) {
                return true;
            }
        }
        return false;
    }
    // Paper end

    public void a(PathfinderGoal pathfindergoal) {
        // Tuinity start - remove streams
        for (Iterator<PathfinderGoalWrapped> iterator = this.d.iterator(); iterator.hasNext();) {
            PathfinderGoalWrapped goalWrapped = iterator.next();
            if (goalWrapped.j() != pathfindergoal) {
                continue;
            }
            if (goalWrapped.g()) {
                goalWrapped.d();
            }
            iterator.remove();
        }
        // Tuinity end
    }

    private static final PathfinderGoal.Type[] PATHFINDER_GOAL_TYPES = PathfinderGoal.Type.values();

    public void doTick() {
        this.e.enter("goalCleanup");
        // Tuinity start - remove streams
        for (Iterator<PathfinderGoalWrapped> iterator = this.d.iterator(); iterator.hasNext();) {
            PathfinderGoalWrapped wrappedGoal = iterator.next();
            if (!wrappedGoal.g()) {
                continue;
            }

            if (!this.goalTypes.hasCommonElements(wrappedGoal.getGoalTypes()) && wrappedGoal.b()) {
                continue;
            }

            wrappedGoal.d();
        }
        // Tuinity end
        this.c.forEach((pathfindergoal_type, pathfindergoalwrapped) -> {
            if (!pathfindergoalwrapped.g()) {
                this.c.remove(pathfindergoal_type);
            }

        });
        this.e.exit();
        this.e.enter("goalUpdate");
        // Tuinity start - remove streams
        goal_update_loop: for (Iterator<PathfinderGoalWrapped> iterator = this.d.iterator(); iterator.hasNext();) {
            PathfinderGoalWrapped wrappedGoal = iterator.next();
            if (wrappedGoal.g()) {
                continue;
            }

            OptimizedSmallEnumSet<PathfinderGoal.Type> wrappedGoalSet = wrappedGoal.getGoalTypes();

            if (this.goalTypes.hasCommonElements(wrappedGoalSet)) {
                continue;
            }

            long iterator1 = wrappedGoalSet.getBackingSet();
            int wrappedGoalSize = wrappedGoalSet.size();
            for (int i = 0; i < wrappedGoalSize; ++i) {
                PathfinderGoal.Type type = PATHFINDER_GOAL_TYPES[Long.numberOfTrailingZeros(iterator1)];
                iterator1 ^= ca.spottedleaf.concurrentutil.util.IntegerUtil.getTrailingBit(iterator1);
                PathfinderGoalWrapped wrapped = this.c.getOrDefault(type, PathfinderGoalSelector.b);
                if (!wrapped.a(wrappedGoal)) {
                    continue goal_update_loop;
                }
            }

            if (!wrappedGoal.a()) {
                continue;
            }

            iterator1 = wrappedGoalSet.getBackingSet();
            wrappedGoalSize = wrappedGoalSet.size();
            for (int i = 0; i < wrappedGoalSize; ++i) {
                PathfinderGoal.Type type = PATHFINDER_GOAL_TYPES[Long.numberOfTrailingZeros(iterator1)];
                iterator1 ^= ca.spottedleaf.concurrentutil.util.IntegerUtil.getTrailingBit(iterator1);
                PathfinderGoalWrapped wrapped = this.c.getOrDefault(type, PathfinderGoalSelector.b);

                wrapped.d();
                this.c.put(type, wrappedGoal);
            }

            wrappedGoal.c();
        }
        // Tuinity end
        this.e.exit();
        this.e.enter("goalTick");
        // Tuinity start - remove streams
        for (Iterator<PathfinderGoalWrapped> iterator = this.d.iterator(); iterator.hasNext();) {
            PathfinderGoalWrapped wrappedGoal = iterator.next();
            if (wrappedGoal.g()) {
                wrappedGoal.e();
            }
        }
        // Tuinity end
        this.e.exit();
    }

    public Stream<PathfinderGoalWrapped> c() {
        return this.d.stream().filter(PathfinderGoalWrapped::g);
    }

    public void a(PathfinderGoal.Type pathfindergoal_type) {
        this.goalTypes.addUnchecked(pathfindergoal_type); // Tuinity - reduce streams
    }

    public void b(PathfinderGoal.Type pathfindergoal_type) {
        this.goalTypes.removeUnchecked(pathfindergoal_type); // Tuinity - reduce streams
    }

    public void a(PathfinderGoal.Type pathfindergoal_type, boolean flag) {
        if (flag) {
            this.b(pathfindergoal_type);
        } else {
            this.a(pathfindergoal_type);
        }

    }
}
