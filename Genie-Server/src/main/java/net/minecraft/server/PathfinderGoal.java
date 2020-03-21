package net.minecraft.server;

import com.tuinity.tuinity.util.OptimizedSmallEnumSet; // Tuinity
import java.util.EnumSet;

public abstract class PathfinderGoal {

    private final OptimizedSmallEnumSet<Type> goalTypes = new OptimizedSmallEnumSet<>(PathfinderGoal.Type.class); // Tuinity - reduce garbage on heap

    public PathfinderGoal() {}

    public abstract boolean a();

    public boolean b() {
        return this.a();
    }

    public boolean E_() {
        return true;
    }

    public void c() {}

    public void d() {
        onTaskReset(); // Paper
    }
    public void onTaskReset() {} // Paper

    public void e() {}

    public void a(EnumSet<PathfinderGoal.Type> enumset) {
        // Tuinity start - reduce garbage on heap
        this.goalTypes.clear();
        this.goalTypes.addAllUnchecked(enumset);
        // Tuinity end - reduce garbage on heap
    }

    public String toString() {
        return this.getClass().getSimpleName();
    }

    // Tuinity start - reduce garbage on heap
    public com.tuinity.tuinity.util.OptimizedSmallEnumSet<PathfinderGoal.Type> getGoalTypes() {
        return this.goalTypes;
        // Tuinity end - reduce garbage on heap
    }

    public static enum Type {

        MOVE, LOOK, JUMP, TARGET;

        private Type() {}
    }
}
