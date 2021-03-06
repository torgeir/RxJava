/**
 * Copyright 2014 Netflix, Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package rx.schedulers;

import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;

import rx.Scheduler;
import rx.Subscription;
import rx.functions.Action0;
import rx.subscriptions.CompositeSubscription;
import rx.subscriptions.Subscriptions;

/**
 * Schedules work on a new thread.
 */
public class NewThreadScheduler extends Scheduler {

    private static final String THREAD_NAME_PREFIX = "RxNewThreadScheduler-";
    private static final RxThreadFactory THREAD_FACTORY = new RxThreadFactory(THREAD_NAME_PREFIX);
    private static final NewThreadScheduler INSTANCE = new NewThreadScheduler();

    static final class RxThreadFactory implements ThreadFactory {
        final String prefix;
        volatile long counter;
        static final AtomicLongFieldUpdater<RxThreadFactory> COUNTER_UPDATER
                = AtomicLongFieldUpdater.newUpdater(RxThreadFactory.class, "counter");

        public RxThreadFactory(String prefix) {
            this.prefix = prefix;
        }
        
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + COUNTER_UPDATER.incrementAndGet(this));
            t.setDaemon(true);
            return t;
        }
    }
    
    /* package */static NewThreadScheduler instance() {
        return INSTANCE;
    }

    private NewThreadScheduler() {

    }

    @Override
    public Worker createWorker() {
        return new NewThreadWorker(THREAD_FACTORY);
    }

    /* package */static class NewThreadWorker extends Scheduler.Worker implements Subscription {
        private final ScheduledExecutorService executor;
        volatile boolean isUnsubscribed;

        /* package */NewThreadWorker(ThreadFactory threadFactory) {
            executor = Executors.newScheduledThreadPool(1, threadFactory);
        }

        @Override
        public Subscription schedule(final Action0 action) {
            return schedule(action, 0, null);
        }

        @Override
        public Subscription schedule(final Action0 action, long delayTime, TimeUnit unit) {
            if (isUnsubscribed) {
                return Subscriptions.empty();
            }
            return scheduleActual(action, delayTime, unit);
        }

        /* package */ScheduledAction scheduleActual(final Action0 action, long delayTime, TimeUnit unit) {
            ScheduledAction run = new ScheduledAction(action);
            Future<?> f;
            if (delayTime <= 0) {
                f = executor.submit(run);
            } else {
                f = executor.schedule(run, delayTime, unit);
            }
            run.add(Subscriptions.from(f));
            
            return run;
        }
        
        /** Remove a child subscription from a composite when unsubscribing. */
        private static final class Remover implements Subscription {
            final Subscription s;
            final CompositeSubscription parent;
            volatile int once;
            static final AtomicIntegerFieldUpdater<Remover> ONCE_UPDATER
                    = AtomicIntegerFieldUpdater.newUpdater(Remover.class, "once");
            
            public Remover(Subscription s, CompositeSubscription parent) {
                this.s = s;
                this.parent = parent;
            }
            
            @Override
            public boolean isUnsubscribed() {
                return s.isUnsubscribed();
            }
            
            @Override
            public void unsubscribe() {
                if (ONCE_UPDATER.compareAndSet(this, 0, 1)) {
                    parent.remove(s);
                }
            }
            
        }
        /** 
         * A runnable that executes an Action0 and can be cancelled
         * The analogue is the Subscriber in respect of an Observer.
         */
        public static final class ScheduledAction implements Runnable, Subscription {
            final CompositeSubscription cancel;
            final Action0 action;
            volatile int once;
            static final AtomicIntegerFieldUpdater<ScheduledAction> ONCE_UPDATER
                    = AtomicIntegerFieldUpdater.newUpdater(ScheduledAction.class, "once");

            public ScheduledAction(Action0 action) {
                this.action = action;
                this.cancel = new CompositeSubscription();
            }

            @Override
            public void run() {
                try {
                    action.call();
                } finally {
                    unsubscribe();
                }
            }

            @Override
            public boolean isUnsubscribed() {
                return cancel.isUnsubscribed();
            }
            
            @Override
            public void unsubscribe() {
                if (ONCE_UPDATER.compareAndSet(this, 0, 1)) {
                    cancel.unsubscribe();
                }
            }
            public void add(Subscription s) {
                cancel.add(s);
            }
            /** 
             * Adds a parent to this ScheduledAction so when it is 
             * cancelled or terminates, it can remove itself from this parent.
             * @param parent 
             */
            public void addParent(CompositeSubscription parent) {
                cancel.add(new Remover(this, parent));
            } 
        }

        @Override
        public void unsubscribe() {
            isUnsubscribed = true;
            executor.shutdownNow();
        }

        @Override
        public boolean isUnsubscribed() {
            return isUnsubscribed;
        }

    }
}
