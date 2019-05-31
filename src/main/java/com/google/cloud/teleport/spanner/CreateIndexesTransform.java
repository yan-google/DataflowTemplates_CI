/*
 * Copyright (C) 2019 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.teleport.spanner;

import com.google.api.gax.longrunning.OperationFuture;
import com.google.cloud.spanner.DatabaseAdminClient;
import com.google.cloud.teleport.spanner.ddl.Ddl;
import com.google.spanner.admin.database.v1.UpdateDatabaseDdlMetadata;
import java.util.List;
import java.util.concurrent.ExecutionException;
import org.apache.beam.sdk.io.gcp.spanner.SpannerAccessor;
import org.apache.beam.sdk.io.gcp.spanner.SpannerConfig;
import org.apache.beam.sdk.options.ValueProvider;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.values.PCollection;

/** A Beam transform that creates indexes for all tables in a Cloud Spanner database. */
class CreateIndexesTransform extends PTransform<PCollection<Ddl>, PCollection<Void>> {

  private final SpannerConfig spannerConfig;
  private final ValueProvider<Boolean> waitForIndexes;

  public CreateIndexesTransform(
      SpannerConfig spannerConfig, ValueProvider<Boolean> waitForIndexes) {
    this.spannerConfig = spannerConfig;
    this.waitForIndexes = waitForIndexes;
  }

  @Override
  public PCollection<Void> expand(PCollection<Ddl> input) {
    return input.apply(
        "Create Indexes",
        ParDo.of(
            new DoFn<Ddl, Void>() {

              private transient SpannerAccessor spannerAccessor;

              @Setup
              public void setup() {
                spannerAccessor = spannerConfig.connectToSpanner();
              }

              @Teardown
              public void teardown() {
                spannerAccessor.close();
              }

              @ProcessElement
              public void processElement(ProcessContext c) {
                Ddl ddl = c.element();
                DatabaseAdminClient databaseAdminClient = spannerAccessor.getDatabaseAdminClient();
                List<String> createIndexStatements = ddl.createIndexStatements();
                if (!createIndexStatements.isEmpty()) {
                  // This just kicks off the index creation, it does not wait for it to complete.
                  OperationFuture<Void, UpdateDatabaseDdlMetadata> op =
                      databaseAdminClient.updateDatabaseDdl(
                          spannerConfig.getInstanceId().get(),
                          spannerConfig.getDatabaseId().get(),
                          createIndexStatements,
                          null);
                  if (waitForIndexes.get()) {
                    try {
                      op.get();
                    } catch (InterruptedException | ExecutionException e) {
                      throw new RuntimeException(e);
                    }
                  }
                }
              }
            }));
  }
}
