package net.minecraft.server;

import com.google.common.collect.Queues;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.annotation.Nullable;

public interface PairedQueue<T, F> {

    @Nullable
    F a();

    boolean a(T t0);

    boolean b();

    public static final class a implements PairedQueue<PairedQueue.b, Runnable> {

        private final List<Queue<Runnable>> a; private final List<Queue<Runnable>> getQueues() { return this.a; } // Tuinity - OBFHELPER

        public a(int i) {
            // Tuinity start - reduce streams
            this.a = new java.util.ArrayList<>(i); // queues
            for (int j = 0; j < i; ++j) {
                this.getQueues().add(new ca.spottedleaf.concurrentutil.queue.MultiThreadedQueue<>()); // use MT queue
            }
            // Tuinity end - reduce streams
        }

        @Nullable
        @Override
        public Runnable a() {
            // Tuinity start - reduce iterator creation
            for (int i = 0, len = this.getQueues().size(); i < len; ++i) {
                Queue<Runnable> queue = this.getQueues().get(i);
                Runnable ret = queue.poll();
                if (ret != null) {
                    return ret;
                }
            }
            return null;
            // Tuinity end - reduce iterator creation
        }

        public boolean a(PairedQueue.b pairedqueue_b) {
            int i = pairedqueue_b.a();

            ((Queue) this.a.get(i)).add(pairedqueue_b);
            return true;
        }

        @Override
        public boolean b() {
            // Tuinity start - reduce streams
            for (int i = 0, len = this.getQueues().size(); i < len; ++i) {
                Queue<Runnable> queue = this.getQueues().get(i);
                if (!queue.isEmpty()) {
                    return false;
                }
            }
            return true;
            // Tuinity end - reduce streams
        }
    }

    public static final class b implements Runnable {

        private final int a;
        private final Runnable b;

        public b(int i, Runnable runnable) {
            this.a = i;
            this.b = runnable;
        }

        public void run() {
            this.b.run();
        }

        public int a() {
            return this.a;
        }
    }

    public static final class c<T> implements PairedQueue<T, T> {

        private final Queue<T> a;

        public c(Queue<T> queue) {
            this.a = queue;
        }

        @Nullable
        @Override
        public T a() {
            return this.a.poll();
        }

        @Override
        public boolean a(T t0) {
            return this.a.add(t0);
        }

        @Override
        public boolean b() {
            return this.a.isEmpty();
        }
    }
}
