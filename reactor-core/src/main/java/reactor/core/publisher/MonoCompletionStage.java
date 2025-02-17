/*
 * Copyright (c) 2016-2022 VMware Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.core.publisher;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.function.BiConsumer;

import reactor.core.CoreSubscriber;
import reactor.core.Exceptions;
import reactor.core.Fuseable;
import reactor.core.Scannable;
import reactor.util.annotation.Nullable;
import reactor.util.context.Context;

/**
 * Emits the value or error produced by the wrapped CompletionStage.
 * <p>
 * Note that if Subscribers cancel their subscriptions, the CompletionStage
 * is not cancelled.
 *
 * @param <T> the value type
 */
final class MonoCompletionStage<T> extends Mono<T>
        implements Fuseable, Scannable {

    final CompletionStage<? extends T> future;

    MonoCompletionStage(CompletionStage<? extends T> future) {
        this.future = Objects.requireNonNull(future, "future");
    }

    @Override
    public void subscribe(CoreSubscriber<? super T> actual) {
        actual.onSubscribe(new MonoCompletionStageSubscription<>(actual, future));
    }

    @Override
    public Object scanUnsafe(Attr key) {
        if (key == Attr.RUN_STYLE) return Attr.RunStyle.ASYNC;
        return null;
    }

    static class MonoCompletionStageSubscription<T> implements InnerProducer<T>,
                                                               Fuseable,
                                                               QueueSubscription<T>,
                                                               BiConsumer<T, Throwable> {

        final CoreSubscriber<? super T>    actual;
        final CompletionStage<? extends T> future;

        volatile int requestedOnce;
        @SuppressWarnings("rawtypes")
        static final AtomicIntegerFieldUpdater<MonoCompletionStageSubscription> REQUESTED_ONCE =
                AtomicIntegerFieldUpdater.newUpdater(MonoCompletionStageSubscription.class, "requestedOnce");

        volatile boolean cancelled;

        MonoCompletionStageSubscription(CoreSubscriber<? super T> actual, CompletionStage<? extends T> future) {
            this.actual = actual;
            this.future = future;
        }

        @Override
        public CoreSubscriber<? super T> actual() {
            return this.actual;
        }

        @Override
        public void accept(@Nullable T value, @Nullable Throwable e) {
            final CoreSubscriber<? super T> actual = this.actual;

            if (this.cancelled) {
                //nobody is interested in the Mono anymore, don't risk dropping errors
                final Context ctx = actual.currentContext();
                if (e == null || e instanceof CancellationException) {
                    //we discard any potential value and ignore Future cancellations
                    Operators.onDiscard(value, ctx);
                }
                else {
                    //we make sure we keep _some_ track of a Future failure AFTER the Mono cancellation
                    Operators.onErrorDropped(e, ctx);
                    //and we discard any potential value just in case both e and v are not null
                    Operators.onDiscard(value, ctx);
                }

                return;
            }

            try {
                if (e instanceof CompletionException) {
                    actual.onError(e.getCause());
                }
                else if (e != null) {
                    actual.onError(e);
                }
                else if (value != null) {
                    actual.onNext(value);
                    actual.onComplete();
                }
                else {
                    actual.onComplete();
                }
            }
            catch (Throwable e1) {
                Operators.onErrorDropped(e1, actual.currentContext());
                throw Exceptions.bubble(e1);
            }
        }

        @Override
        public void request(long n) {
            if (this.cancelled) {
                return;
            }

            if (this.requestedOnce == 1 || !REQUESTED_ONCE.compareAndSet(this, 0 , 1)) {
                return;
            }

            future.whenComplete(this);
        }

        @Override
        public void cancel() {
            this.cancelled = true;

            final CompletionStage<? extends T> future = this.future;
            if (future instanceof CompletableFuture) {
                //noinspection unchecked
                ((CompletableFuture<? extends T>) future).cancel(true);
            }
        }

        @Override
        public int requestFusion(int requestedMode) {
            return NONE;
        }

        @Override
        public T poll() {
           return null;
        }

        @Override
        public int size() {
            return 0;
        }

        @Override
        public boolean isEmpty() {
            return true;
        }

        @Override
        public void clear() {
        }
    }
}
