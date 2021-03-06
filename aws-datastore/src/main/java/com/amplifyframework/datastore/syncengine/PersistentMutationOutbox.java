/*
 * Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.amplifyframework.datastore.syncengine;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.core.util.ObjectsCompat;

import com.amplifyframework.AmplifyException;
import com.amplifyframework.core.Amplify;
import com.amplifyframework.core.model.Model;
import com.amplifyframework.datastore.DataStoreException;
import com.amplifyframework.datastore.storage.LocalStorageAdapter;
import com.amplifyframework.datastore.storage.StorageItemChange;
import com.amplifyframework.logging.Logger;

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Semaphore;

import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.subjects.PublishSubject;
import io.reactivex.subjects.Subject;

/*
 * The {@link MutationOutbox} is a persistently-backed in-order staging ground
 * for changes that have already occurred in the storage adapter, and need
 * to be synchronized with a remote GraphQL API, via (a) GraphQL mutation(s).
 *
 * This component is an "offline mutation queue,"; items in the mutation outbox are observed,
 * and written out over the network. When an item is written out over the network successfully,
 * it is safe to remove it from this outbox.
 */
final class PersistentMutationOutbox implements MutationOutbox {
    private static final Logger LOG = Amplify.Logging.forNamespace("amplify:aws-datastore");

    private final LocalStorageAdapter storage;
    private final LinkedList<PendingMutation<? extends Model>> mutationQueue;
    private final Set<TimeBasedUuid> inFlightMutations;
    private final PendingMutation.Converter converter;
    private final Subject<OutboxEvent> events;
    private final Semaphore semaphore;

    PersistentMutationOutbox(@NonNull final LocalStorageAdapter localStorageAdapter) {
        this.storage = Objects.requireNonNull(localStorageAdapter);
        this.mutationQueue = new LinkedList<>();
        this.inFlightMutations = new HashSet<>();
        this.converter = new GsonPendingMutationConverter();
        this.events = PublishSubject.<OutboxEvent>create().toSerialized();
        this.semaphore = new Semaphore(1);
    }

    @Override
    public boolean hasPendingMutation(@NonNull String modelId) {
        Objects.requireNonNull(modelId);
        return nextMutationForModelId(modelId) != null;
    }

    @NonNull
    @Override
    public <T extends Model> Completable enqueue(@NonNull PendingMutation<T> incomingMutation) {
        Objects.requireNonNull(incomingMutation);
        // If there is no existing mutation for the model, then just apply the incoming
        // mutation, and be done with this.
        String modelId = incomingMutation.getMutatedItem().getId();
        @SuppressWarnings("unchecked")
        PendingMutation<T> existingMutation = (PendingMutation<T>) nextMutationForModelId(modelId);
        if (existingMutation == null || inFlightMutations.contains(existingMutation.getMutationId())) {
            return save(incomingMutation).andThen(notifyContentAvailable());
        }
        switch (incomingMutation.getMutationType()) {
            case CREATE:
                return handleCreation();
            case UPDATE:
                return handleUpdate(existingMutation, incomingMutation);
            case DELETE:
                return handleDeletion(existingMutation, incomingMutation);
            default:
                return unknownMutationType(incomingMutation.getMutationType());
        }
    }

    @VisibleForTesting
    @Nullable // If there is no next mutation for the model ID.
    PendingMutation<? extends Model> nextMutationForModelId(String modelId) {
        for (PendingMutation<? extends Model> mutation : mutationQueue) {
            if (mutation.getMutatedItem().getId().equals(modelId)) {
                return mutation;
            }
        }
        return null;
    }

    private Completable handleCreation() {
        return Completable.error(new DataStoreException(
            "Attempted to enqueue a model creation, but there is already a pending mutation for that model ID.",
            "Please report at https://github.com/aws-amplify/amplify-android/issues."
        ));
    }

    private <T extends Model> Completable handleUpdate(
            PendingMutation<T> existingMutation, PendingMutation<T> incomingMutation) {
        switch (existingMutation.getMutationType()) {
            case CREATE:
            case UPDATE:
                return overwriteExisting(existingMutation, incomingMutation, existingMutation.getMutationType());
            case DELETE:
                return Completable.error(new DataStoreException(
                    "Attempted to enqueue a model mutation, but that model already had a delete mutation pending.",
                    "This should not be possible. Please report on GitHub issues."
                ));
            default:
                return unknownMutationType(existingMutation.getMutationType());
        }
    }

    private <T extends Model> Completable handleDeletion(
            PendingMutation<T> existingMutation, PendingMutation<T> incomingMutation) {
        switch (existingMutation.getMutationType()) {
            case CREATE:
                return remove(existingMutation.getMutationId());
            case UPDATE:
            case DELETE:
                return overwriteExisting(existingMutation, incomingMutation, PendingMutation.Type.DELETE);
            default:
                return unknownMutationType(existingMutation.getMutationType());
        }
    }

    private Completable unknownMutationType(PendingMutation.Type unknownType) {
        return Completable.error(new DataStoreException(
            "Existing mutation of unknown type = " + unknownType,
            "Please report at https://github.com/aws-amplify/amplify-android/issues."
        ));
    }

    // Type is sometimes the existing type, sometimes the incoming type.
    private <T extends Model> Completable overwriteExisting(
            PendingMutation<T> existingMutation, PendingMutation<T> incomingMutation, PendingMutation.Type type) {
        // Keep the old mutation ID, but update the contents of that mutation.
        // Now, it will have the contents of the incoming update mutation.
        TimeBasedUuid id = existingMutation.getMutationId();
        T item = incomingMutation.getMutatedItem();
        Class<T> clazz = incomingMutation.getClassOfMutatedItem();
        return save(PendingMutation.instance(id, item, clazz, type))
            .andThen(notifyContentAvailable());
    }

    private <T extends Model> Completable save(PendingMutation<T> pendingMutation) {
        return Completable.defer(() -> Completable.create(subscriber -> {
            semaphore.acquire();
            storage.save(
                converter.toRecord(pendingMutation),
                StorageItemChange.Initiator.SYNC_ENGINE,
                saved -> {
                    // The return value is StorageItemChange, referring to a PersistentRecord
                    // that was saved. We could "unwrap" a PendingMutation from that PersistentRecord,
                    // to get identically the thing that was saved. But we know the save succeeded.
                    // So, let's skip the unwrapping, and use the thing that was enqueued,
                    // the pendingMutation, directly.
                    updateExistingQueueItemOrAppendNew(pendingMutation);
                    LOG.info("Successfully enqueued " + pendingMutation);
                    semaphore.release();
                    subscriber.onComplete();
                },
                failure -> {
                    semaphore.release();
                    subscriber.onError(failure);
                }
            );
        }));
    }

    private <T extends Model> void updateExistingQueueItemOrAppendNew(PendingMutation<T> pendingMutation) {
        // If there is already a mutation with same ID in the queue,
        // we'll go find it, and then update it, with this contents.
        for (int position = 0; position < mutationQueue.size(); position++) {
            if (mutationQueue.get(position).getMutationId().equals(pendingMutation.getMutationId())) {
                mutationQueue.set(position, pendingMutation);
                return;
            }
        }
        // Otherwise, just add it to the end of the queue.
        mutationQueue.addLast(pendingMutation);
    }

    @NonNull
    @Override
    public Completable remove(@NonNull TimeBasedUuid pendingMutationId) {
        Objects.requireNonNull(pendingMutationId);
        PendingMutation<? extends Model> pendingMutation = findPendingMutationById(pendingMutationId);
        if (pendingMutation == null) {
            return Completable.error(new DataStoreException(
                "Outbox was asked to remove a mutation with ID = " + pendingMutationId + ". " +
                    "However, there was no mutation with that ID in the outbox, to begin with.",
                AmplifyException.REPORT_BUG_TO_AWS_SUGGESTION
            ));
        }
        return Completable.defer(() -> Completable.create(subscriber -> {
            semaphore.acquire();
            storage.delete(
                converter.toRecord(pendingMutation),
                StorageItemChange.Initiator.SYNC_ENGINE,
                ignored -> {
                    removeFromQueue(pendingMutation.getMutationId());
                    inFlightMutations.remove(pendingMutationId);
                    LOG.info("Successfully removed from mutations outbox" + pendingMutation);
                    if (!mutationQueue.isEmpty()) {
                        notifyContentAvailable();
                    }
                    semaphore.release();
                    subscriber.onComplete();
                },
                failure -> {
                    semaphore.release();
                    subscriber.onError(failure);
                }
            );
        }));
    }

    private void removeFromQueue(TimeBasedUuid mutationId) {
        Iterator<PendingMutation<? extends Model>> iterator = mutationQueue.iterator();
        while (iterator.hasNext()) {
            if (ObjectsCompat.equals(iterator.next().getMutationId(), mutationId)) {
                iterator.remove();
            }
        }
    }

    @NonNull
    @Override
    public Completable load() {
        return Completable.defer(() -> Completable.create(emitter -> {
            semaphore.acquire();
            inFlightMutations.clear();
            mutationQueue.clear();
            storage.query(PendingMutation.PersistentRecord.class,
                results -> {
                    while (results.hasNext()) {
                        try {
                            mutationQueue.addLast(converter.fromRecord(results.next()));
                        } catch (DataStoreException conversionFailure) {
                            semaphore.release();
                            emitter.onError(conversionFailure);
                            return;
                        }
                    }
                    semaphore.release();
                    emitter.onComplete();
                },
                failure -> {
                    semaphore.release();
                    emitter.onError(failure);
                }
            );
        }));
    }

    @NonNull
    @Override
    public Observable<OutboxEvent> events() {
        return events;
    }

    private Completable notifyContentAvailable() {
        return Completable.fromAction(() -> events.onNext(OutboxEvent.CONTENT_AVAILABLE));
    }

    @Nullable
    @Override
    public PendingMutation<? extends Model> peek() {
        return mutationQueue.peekFirst();
    }

    @NonNull
    @Override
    public Completable markInFlight(@NonNull TimeBasedUuid pendingMutationId) {
        return Completable.create(emitter -> {
            PendingMutation<? extends Model> mutation = findPendingMutationById(pendingMutationId);
            if (mutation != null) {
                inFlightMutations.add(mutation.getMutationId());
                emitter.onComplete();
                return;
            }
            emitter.onError(new DataStoreException(
                "Outbox was asked to mark a mutation with ID = " + pendingMutationId + " as in-flight. " +
                    "However, there was no mutation with that ID in the outbox, to begin with.",
                AmplifyException.REPORT_BUG_TO_AWS_SUGGESTION
            ));
        });
    }

    @Nullable // When there is no match.
    private PendingMutation<? extends Model> findPendingMutationById(TimeBasedUuid pendingMutationId) {
        for (PendingMutation<? extends Model> pendingMutation : mutationQueue) {
            if (pendingMutation.getMutationId().equals(pendingMutationId)) {
                return pendingMutation;
            }
        }
        return null;
    }
}
