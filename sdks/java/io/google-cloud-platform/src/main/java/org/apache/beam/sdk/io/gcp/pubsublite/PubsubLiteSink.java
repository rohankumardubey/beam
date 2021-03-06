/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.sdk.io.gcp.pubsublite;

import com.google.api.core.ApiFuture;
import com.google.api.core.ApiFutureCallback;
import com.google.api.core.ApiFutures;
import com.google.api.core.ApiService.Listener;
import com.google.api.core.ApiService.State;
import com.google.api.gax.rpc.ApiException;
import com.google.cloud.pubsublite.Message;
import com.google.cloud.pubsublite.MessageMetadata;
import com.google.cloud.pubsublite.internal.CheckedApiException;
import com.google.cloud.pubsublite.internal.ExtractStatus;
import com.google.cloud.pubsublite.internal.Publisher;
import com.google.cloud.pubsublite.internal.wire.SystemExecutors;
import com.google.cloud.pubsublite.proto.PubSubMessage;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;
import org.apache.beam.sdk.io.gcp.pubsublite.PublisherOrError.Kind;
import org.apache.beam.sdk.transforms.DoFn;

/** A sink which publishes messages to Pub/Sub Lite. */
@SuppressWarnings({
  "nullness" // TODO(https://issues.apache.org/jira/browse/BEAM-10402)
})
class PubsubLiteSink extends DoFn<PubSubMessage, Void> {
  private final PublisherOptions options;

  @GuardedBy("this")
  private transient PublisherOrError publisherOrError;

  // Whenever outstanding is decremented, notify() must be called.
  @GuardedBy("this")
  private transient int outstanding;

  @GuardedBy("this")
  private transient Deque<CheckedApiException> errorsSinceLastFinish;

  PubsubLiteSink(PublisherOptions options) {
    this.options = options;
  }

  @Setup
  public void setup() throws ApiException {
    Publisher<MessageMetadata> publisher;
    if (options.usesCache()) {
      publisher = PerServerPublisherCache.PUBLISHER_CACHE.get(options);
    } else {
      publisher = Publishers.newPublisher(options);
    }
    synchronized (this) {
      outstanding = 0;
      errorsSinceLastFinish = new ArrayDeque<>();
      publisherOrError = PublisherOrError.ofPublisher(publisher);
    }
    // cannot declare in inner class since 'this' means something different.
    Consumer<Throwable> onFailure =
        t -> {
          synchronized (this) {
            publisherOrError = PublisherOrError.ofError(ExtractStatus.toCanonical(t));
          }
        };
    publisher.addListener(
        new Listener() {
          @Override
          public void failed(State s, Throwable t) {
            onFailure.accept(t);
          }
        },
        SystemExecutors.getFuturesExecutor());
    if (!options.usesCache()) {
      publisher.startAsync();
    }
  }

  private synchronized void decrementOutstanding() {
    --outstanding;
    notify();
  }

  @ProcessElement
  public synchronized void processElement(@Element PubSubMessage message)
      throws CheckedApiException {
    ++outstanding;
    if (publisherOrError.getKind() == Kind.ERROR) {
      throw publisherOrError.error();
    }
    ApiFuture<MessageMetadata> future =
        publisherOrError.publisher().publish(Message.fromProto(message));
    // cannot declare in inner class since 'this' means something different.
    Consumer<Throwable> onFailure =
        t -> {
          synchronized (this) {
            decrementOutstanding();
            errorsSinceLastFinish.push(ExtractStatus.toCanonical(t));
          }
        };
    ApiFutures.addCallback(
        future,
        new ApiFutureCallback<MessageMetadata>() {
          @Override
          public void onSuccess(MessageMetadata messageMetadata) {
            decrementOutstanding();
          }

          @Override
          public void onFailure(Throwable t) {
            onFailure.accept(t);
          }
        },
        SystemExecutors.getFuturesExecutor());
  }

  // Intentionally don't flush on bundle finish to allow multi-sink client reuse.
  @FinishBundle
  public synchronized void finishBundle() throws CheckedApiException, InterruptedException {
    while (outstanding > 0) {
      wait();
    }
    if (!errorsSinceLastFinish.isEmpty()) {
      CheckedApiException canonical = errorsSinceLastFinish.pop();
      while (!errorsSinceLastFinish.isEmpty()) {
        canonical.addSuppressed(errorsSinceLastFinish.pop());
      }
      throw canonical;
    }
    if (publisherOrError.getKind() == Kind.ERROR) {
      throw publisherOrError.error();
    }
  }
}
