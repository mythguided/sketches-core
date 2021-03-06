/*
 * Copyright 2018, Yahoo! Inc. Licensed under the terms of the
 * Apache License 2.0. See LICENSE file at the project root for terms.
 */

package com.yahoo.sketches.theta;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background propagation thread. Propagates a given sketch or a hash value from local threads
 * buffers into the shared sketch which stores the most up-to-date estimation of number of unique
 * items. This propagation is done at the background by dedicated threads, which allows
 * application threads to continue updating their local buffer.
 *
 * @author eshcar
 */
class BackgroundThetaPropagation implements Runnable {
  static private final int NUM_POOL_THREADS = 3;

  /**
   * Pool of threads to serve *all* propagation tasks in the system.
   */
  static final ExecutorService propagationExecutorService =
      Executors.newWorkStealingPool(NUM_POOL_THREADS);

  /**
   * Shared sketch to absorb the data
   */
  private final SharedThetaSketch sharedThetaSketch;
  /**
   * Propagation flag of local buffer that is being processed.
   * It is the synchronization primitive to coordinate the work of the propagation with the
   * local buffer.
   * Updated when the propagation completes.
   */
  private final AtomicBoolean localPropagationInProgress;
  /**
   * Sketch to be propagated to shared sketch. Can be null if only a single hash is propagated
   */
  private final Sketch sketchIn;
  /**
   * Hash of the datum to be propagated to shared sketch. Can be SharedThetaSketch.NOT_SINGLE_HASH
   * if the data is propagated through a sketch.
   */
  private final long singleHash;
  /**
   * The propagation epoch. The data can be propagated only within the context of this epoch.
   * The data should not be propagated if this epoch is not equal to the
   * shared sketch epoch.
   */
  private final long epoch;

  public BackgroundThetaPropagation(final SharedThetaSketch sharedThetaSketch,
      final AtomicBoolean localPropagationInProgress, final Sketch sketchIn, final long singleHash,
      final long epoch) {
    this.sharedThetaSketch = sharedThetaSketch;
    this.localPropagationInProgress = localPropagationInProgress;
    this.sketchIn = sketchIn;
    this.singleHash = singleHash;
    this.epoch = epoch;
  }

  /**
   * Propagation protocol:
   * 1) start propagation: this ensure mutual exclusion.
   *    No other thread can update the shared sketch while propagation is in progress
   * 2) validate propagation is executed at the context of the right epoch, otherwise abort
   * 3) handle propagation: either of a single hash or of a sketch
   * 4) complete propagation: end mutual exclusion block
   */
  @Override public void run() {
    // 1) start propagation: this ensure mutual exclusion.
    sharedThetaSketch.startPropagation();
    // At this point we are sure no other thread can update the shared sketch while propagation is
    // in progress

    // 2) validate propagation is executed at the context of the right epoch, otherwise abort
    if (!sharedThetaSketch.validateEpoch(epoch)) {
      // invalid epoch - should not propagate
      sharedThetaSketch.endPropagation(null);
      return;
    }

    // 3) handle propagation: either of a single hash or of a sketch
    if (singleHash != SharedThetaSketch.NOT_SINGLE_HASH) {
      sharedThetaSketch.updateSingle(singleHash); // backdoor update, hash function is bypassed
    } else if (sketchIn != null) {
      final long volTheta = sharedThetaSketch.getVolatileTheta();
      assert volTheta <= sketchIn.getThetaLong() :
          "volTheta = " + volTheta + ", bufTheta = " + sketchIn.getThetaLong();

      // propagate values from input sketch one by one
      final long[] cacheIn = sketchIn.getCache();

      if (sketchIn.isOrdered()) { //Ordered compact, Use early stop
        for (final long hashIn : cacheIn) {
          if (hashIn >= volTheta) {
            break; //early stop
          }
          sharedThetaSketch.updateSingle(hashIn); // backdoor update, hash function is bypassed
        }
      } else { //not ordered, also may have zeros (gaps) in the array.
        for (final long hashIn : cacheIn) {
          if (hashIn > 0) {
            sharedThetaSketch.updateSingle(hashIn); // backdoor update, hash function is bypassed
          }
        }
      }
    }

    // 4) complete propagation: end mutual exclusion block
    sharedThetaSketch.endPropagation(localPropagationInProgress);
  }

}
